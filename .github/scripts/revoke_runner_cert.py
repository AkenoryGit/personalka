"""Отзыв временного сертификата разработчика, который Xcode создал на машине GitHub (шаг 5.3ж).

Каждая сборка для iPhone идёт на чистом Mac GitHub: в его связке ключей нет сертификата «Apple Development», и Xcode
по ключу App Store Connect API создаёт новый. Закрытый ключ остаётся на машине и пропадает вместе с ней, а сертификат
висит в аккаунте — после нескольких сборок Apple отвечает «maximum number of certificates», и сборка падает.

В конце сборки скрипт находит сертификаты «Apple Development» в связке ключей этой машины и отзывает в аккаунте
ровно их — по серийному номеру. Чужие сертификаты (Mac Дмитрия, сертификаты выпуска) не трогаются. Выпуск в TestFlight
подписан облачным сертификатом Apple и от отзыва не зависит.

Запуск: python3 revoke_runner_cert.py <путь к .p8>  (ASC_KEY_ID, ASC_ISSUER_ID — в окружении).
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

import jwt
from cryptography import x509

API = "https://api.appstoreconnect.apple.com/v1"


def token(key_path: str) -> str:
    with open(key_path) as f:
        key = f.read()
    now = int(time.time())
    payload = {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 600, "aud": "appstoreconnect-v1"}
    return jwt.encode(payload, key, algorithm="ES256", headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"})


def call(method: str, url: str, auth: str):
    req = urllib.request.Request(url, method=method, headers={"Authorization": f"Bearer {auth}"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        body = resp.read()
        return json.loads(body) if body else None


def local_serials() -> set:
    out = subprocess.run(
        ["security", "find-certificate", "-a", "-c", "Apple Development", "-p"],
        capture_output=True, text=True,
    ).stdout
    serials = set()
    for block in out.split("-----END CERTIFICATE-----"):
        if "-----BEGIN CERTIFICATE-----" not in block:
            continue
        pem = (block.strip() + "\n-----END CERTIFICATE-----\n").encode()
        cert = x509.load_pem_x509_certificate(pem)
        serials.add(format(cert.serial_number, "X"))
    return serials


def main() -> None:
    serials = local_serials()
    if not serials:
        print("::notice title=iPhone: сертификат::Временного сертификата на машине нет — отзывать нечего")
        return
    auth = token(sys.argv[1])
    listed = call("GET", f"{API}/certificates?filter[certificateType]=DEVELOPMENT,IOS_DEVELOPMENT&limit=200", auth)
    revoked = []
    for item in listed.get("data", []):
        serial = item["attributes"].get("serialNumber", "").upper().lstrip("0")
        if serial in {s.lstrip("0") for s in serials}:
            call("DELETE", f"{API}/certificates/{item['id']}", auth)
            revoked.append(serial)
    print(f"::notice title=iPhone: сертификат::Временный сертификат машины GitHub отозван ({len(revoked)})")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # отзыв не должен ронять выпуск: сборка уже выгружена
        print(f"::warning title=iPhone: сертификат::Не удалось отозвать временный сертификат — {e}")
