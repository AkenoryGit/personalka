# Проверка чистки ящика backup_mail.clean_box на поддельном IMAP: python3 -I deploy/test_backup_mail.py
import datetime
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import backup_mail  # noqa: E402


class FakeImap:
    """Папки с письмами (uid, тема, дата); SEARCH по метке в теме и BEFORE — по дате, как IMAP."""

    def __init__(self, boxes):
        self.boxes = boxes
        self.current = None
        self.deleted = set()

    def list(self):
        lines = [b'(\\HasNoChildren) "/" "INBOX"', b'(\\Noselect \\HasChildren) "/" "Archive"']
        lines += [b'(\\HasNoChildren) "/" "' + name.encode() + b'"' for name in self.boxes if name != "INBOX"]
        return "OK", lines

    def select(self, name):
        self.current = name.strip('"')
        return ("OK", [b"1"]) if self.current in self.boxes else ("NO", [b""])

    def uid(self, command, *args):
        mails = self.boxes[self.current]
        if command == "SEARCH":
            query = args[1]
            before = datetime.datetime.strptime(query.split("BEFORE ")[1].rstrip(")"), "%d-%b-%Y").date()
            tag = query.split('"')[1]
            found = [uid for uid, (subject, day) in mails.items() if tag in subject and day < before]
            return "OK", [" ".join(found).encode()]
        if command == "FETCH":
            subject = mails[args[0].decode()][0]
            header = f"Subject: {subject}\r\n\r\n"
            return "OK", [(b"1 (BODY[HEADER.FIELDS (SUBJECT)] {10}", header.encode()), b")"]
        if command == "STORE":
            self.deleted.add((self.current, args[0].decode()))
            return "OK", []
        raise AssertionError(command)

    def expunge(self):
        for folder, uid in list(self.deleted):
            self.boxes[folder].pop(uid, None)
        return "OK", []

    def close(self):
        self.current = None


class CleanTest(unittest.TestCase):
    def test_only_old_backups_in_all_folders(self):
        today = datetime.date(2026, 11, 30)
        day = lambda n: today - datetime.timedelta(days=n)  # noqa: E731
        tag = "[personalka-backup]"
        imap = FakeImap({
            "INBOX": {
                "1": (f"Персоналка: копия базы 31.10.2026 {tag}", day(30)),
                "2": (f"Персоналка: копия базы 01.11.2026 {tag}", day(29)),
                "3": ("Planovo: копия базы", day(60)),
                "4": (f"Персоналка: копия базы · часть 2 из 2 {tag}", day(45)),
                # Поиск сервера шире метки в квадратных скобках — такое письмо не трогаем.
                "5": ("Вопрос про personalka-backup", day(90)),
            },
            "Sent": {"7": (f"Персоналка: копия базы {tag}", day(31)), "8": (f"Персоналка {tag}", day(1))},
        })
        self.assertEqual(backup_mail.clean_box(imap, 30, today), 3)
        self.assertEqual(sorted(imap.boxes["INBOX"]), ["2", "3", "5"])
        self.assertEqual(sorted(imap.boxes["Sent"]), ["8"])


if __name__ == "__main__":
    unittest.main()
