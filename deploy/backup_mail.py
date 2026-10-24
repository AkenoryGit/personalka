#!/usr/bin/env python3
# Письма с ночной копией базы (ТЗ 7.5) — для deploy/backup.sh; только стандартная библиотека Python.
#
#   backup_mail.py send ТЕМА ФАЙЛ_ТЕКСТА ВЛОЖЕНИЕ…  — одно письмо на каждое вложение («часть 1 из 2»)
#   backup_mail.py clean ДНЕЙ                       — удалить письма копий старше ДНЕЙ во всех папках ящика
#   backup_mail.py alert ТЕМА ТЕКСТ                 — письмо администратору (TRENER_ADMIN_EMAIL)
#
# Ящик — из /etc/trener/trener.env (TRENER_SMTP_*, его пишет setup.sh): копии уходят в тот же ящик, откуда
# отправлены, — туда же, куда ночные копии Planovo. Чистка по IMAP трогает только письма с меткой TAG в теме,
# поэтому копии Planovo и любые другие письма остаются. TRENER_BACKUP_OUTBOX=папка — письма файлами .eml
# вместо отправки, чистка пропускается (проверка установки в CI).
import datetime
import email.header
import email.message
import email.utils
import imaplib
import os
import re
import smtplib
import ssl
import sys

TAG = "personalka-backup"
ENV_FILE = os.environ.get("TRENER_ENV", "/etc/trener/trener.env")


def read_env(path):
    """Строки ИМЯ="значение" без исполнения файла (как read_env в setup.sh)."""
    values = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = re.match(r'^([A-Z_][A-Z0-9_]*)="?(.*?)"?$', line.rstrip("\n"))
            if m:
                values[m.group(1)] = m.group(2)
    return values


class Box:
    def __init__(self, env):
        self.host = env.get("TRENER_SMTP_HOST", "")
        self.user = env.get("TRENER_SMTP_USER", "")
        self.password = env.get("TRENER_SMTP_PASSWORD", "")
        self.port = int(env.get("TRENER_SMTP_PORT") or 465)
        self.tls = (env.get("TRENER_SMTP_TLS") or "").lower()
        self.sender = env.get("TRENER_MAIL_FROM") or f"Персоналка <{self.user}>"
        self.admin = env.get("TRENER_ADMIN_EMAIL", "")
        # Mail.ru: smtp.mail.ru → imap.mail.ru, 993.
        self.imap_host = env.get("TRENER_IMAP_HOST") or re.sub(r"^smtp\.", "imap.", self.host)

    def configured(self):
        return bool(self.host and self.user and self.password)


def message(box, to, subject, text, attachment=None):
    msg = email.message.EmailMessage()
    msg["From"] = box.sender
    msg["To"] = to
    msg["Subject"] = subject
    msg["Date"] = email.utils.formatdate(localtime=True)
    msg["Message-ID"] = email.utils.make_msgid(domain=box.user.split("@")[-1] or "localhost")
    msg.set_content(text)
    if attachment:
        with open(attachment, "rb") as f:
            msg.add_attachment(f.read(), maintype="application", subtype="octet-stream",
                               filename=os.path.basename(attachment))
    return msg


def deliver(box, messages):
    outbox = os.environ.get("TRENER_BACKUP_OUTBOX")
    if outbox:
        os.makedirs(outbox, exist_ok=True)
        for msg in messages:
            with open(os.path.join(outbox, f"{len(os.listdir(outbox)) + 1:03d}.eml"), "wb") as f:
                f.write(bytes(msg))
        return
    if not box.configured():
        raise SystemExit("нет ящика: TRENER_SMTP_HOST, TRENER_SMTP_USER, TRENER_SMTP_PASSWORD в " + ENV_FILE)
    context = ssl.create_default_context()
    mode = box.tls or ("ssl" if box.port == 465 else "starttls")
    if mode in ("ssl", "tls"):
        smtp = smtplib.SMTP_SSL(box.host, box.port, context=context, timeout=120)
    else:
        smtp = smtplib.SMTP(box.host, box.port, timeout=120)
        if mode == "starttls":
            smtp.starttls(context=context)
    with smtp:
        smtp.login(box.user, box.password)
        for msg in messages:
            smtp.send_message(msg)


def send(box, subject, body_file, parts):
    with open(body_file, encoding="utf-8") as f:
        body = f.read()
    messages = []
    for i, part in enumerate(parts, 1):
        suffix = f" · часть {i} из {len(parts)}" if len(parts) > 1 else ""
        messages.append(message(box, box.user, f"{subject}{suffix} [{TAG}]", body, part))
    deliver(box, messages)


def alert(box, subject, text):
    if not box.admin:
        raise SystemExit("нет TRENER_ADMIN_EMAIL — письмо о сбое некому отправить")
    deliver(box, [message(box, box.admin, subject, text)])


LIST_LINE = re.compile(rb'^\((?P<flags>[^)]*)\) (?P<delim>"[^"]*"|NIL) (?P<name>.+)$')


def folders(imap):
    """Все папки, которые можно открыть, — имена как их прислал сервер (в кавычках, если были)."""
    status, lines = imap.list()
    if status != "OK":
        raise RuntimeError("IMAP LIST: " + status)
    result = []
    for line in lines:
        if not isinstance(line, bytes):
            continue
        m = LIST_LINE.match(line)
        if m and b"\\noselect" not in m.group("flags").lower():
            result.append(m.group("name").decode("ascii"))
    return result


def clean_box(imap, days, today=None):
    """Удаляет письма с меткой TAG в теме старше days дней во всех папках. Возвращает число удалённых."""
    today = today or datetime.date.today()
    # Остаются копии за последние days дней, сегодняшняя включительно: с 29 прошлыми — ровно 30.
    before = (today - datetime.timedelta(days=days - 1)).strftime("%d-%b-%Y")
    deleted = 0
    for folder in folders(imap):
        status, _ = imap.select(folder)
        if status != "OK":
            continue
        status, data = imap.uid("SEARCH", None, f'(SUBJECT "{TAG}" BEFORE {before})')
        uids = data[0].split() if status == "OK" and data and data[0] else []
        found = []
        for uid in uids:
            # Поиск по теме у сервера может быть шире — тему проверяем сами, чужое письмо не удаляется.
            status, fetched = imap.uid("FETCH", uid, "(BODY.PEEK[HEADER.FIELDS (SUBJECT)])")
            header = b"".join(p[1] for p in fetched if isinstance(p, tuple)) if status == "OK" else b""
            subject = str(email.header.make_header(email.header.decode_header(
                email.message_from_bytes(header).get("Subject", ""))))
            if f"[{TAG}]" in subject:
                found.append(uid)
        for uid in found:
            imap.uid("STORE", uid, "+FLAGS", "(\\Deleted)")
        if found:
            imap.expunge()
            deleted += len(found)
        imap.close()
    return deleted


def clean(box, days):
    if os.environ.get("TRENER_BACKUP_OUTBOX"):
        print("чистка ящика пропущена: письма — файлами")
        return
    if not box.configured():
        raise SystemExit("нет ящика для чистки: TRENER_SMTP_* в " + ENV_FILE)
    with imaplib.IMAP4_SSL(box.imap_host, 993, ssl_context=ssl.create_default_context(), timeout=120) as imap:
        imap.login(box.user, box.password)
        print(f"удалено писем старше {days} дней: {clean_box(imap, days)}")
        imap.logout()


def main(argv):
    box = Box(read_env(ENV_FILE))
    command = argv[1] if len(argv) > 1 else ""
    if command == "send" and len(argv) >= 5:
        send(box, argv[2], argv[3], argv[4:])
    elif command == "clean" and len(argv) == 3:
        clean(box, int(argv[2]))
    elif command == "alert" and len(argv) == 4:
        alert(box, argv[2], argv[3])
    else:
        raise SystemExit(__doc__ or "backup_mail.py send|clean|alert …")


if __name__ == "__main__":
    main(sys.argv)
