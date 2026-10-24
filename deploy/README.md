# Сервер на VPS: установка и выкладка

Сервер «Персоналки» ставится рядом с Planovo, но полностью отдельно (ТЗ 7.4): своя служба `trener.service`, свой пользователь `trener`, своя база `trener` в том же PostgreSQL 16, свой поддомен в том же Caddy. Planovo при этом не трогается — ни его служба, ни база, ни его блок в Caddyfile.

Шаг 2.7 подготовил всё это файлами; на сервер ничего не ставилось. Установка — шаг 2.7а, когда будут поддомен и доступ к VPS.

| Файл | Куда на VPS | Что |
| --- | --- | --- |
| [`trener.service`](trener.service) | `/etc/systemd/system/` | Служба: пользователь `trener`, Java до 384 МБ, перезапуск через 5 с, урезанные права |
| [`trener-alert.service`](trener-alert.service) | `/etc/systemd/system/` | Письмо, когда служба упала 5 раз за 10 минут и больше не перезапускается |
| [`trener.env.example`](trener.env.example) | `/etc/trener/trener.env` | Настройки: база, домен, порт, почта; права только для службы |
| [`setup.sql`](setup.sql) | — | Роль и база `trener`, один раз |
| [`Caddyfile`](Caddyfile) | дописать в общий Caddyfile | Поддомен → `127.0.0.1:8090`, сертификат Caddy выпускает сам |
| [`server.sh`](server.sh) | `/opt/trener/bin/` (кладёт выкладка) | Переключение версии, проверка `/health`, откат |

## Как идёт выкладка

1. **«Сборка»** (`build.yml`) на `main`: тесты всех модулей, в том числе сервера на PostgreSQL 16, и `personalka-server.jar` в релизе «Последняя сборка» рядом с APK.
2. **«Выкладка сервера»** (`deploy.yml`) запускается сама после зелёной «Сборки» в `main` и вручную (Actions → «Выкладка сервера» → Run workflow): берёт jar из релиза, по SSH кладёт его в `/opt/trener/releases/<N>/` и вызывает `server.sh switch <N>`.
3. **`server.sh`** переводит ссылку `/opt/trener/current` на новую версию, перезапускает службу и ждёт до 60 секунд ответа `/health` с этим номером версии. Не дождался — возвращает прежнюю версию сам и помечает новую как упавшую; выкладка в Actions краснеет. Хранятся 5 последних версий.
4. **«Откат сервера»** (`rollback.yml`) — ручная кнопка: Actions → «Откат сервера» → Run workflow. Включает ближайшую младшую версию, которая не падала.

Пока на GitHub не задана переменная `TRENER_SSH_HOST`, обе выкладки пропускаются — сборка и APK от этого не зависят.

`/health` отвечает 200 и тремя строками — `ok`, `версия 0.1.N`, `схема M`; если база не отвечает — 503 «база не отвечает». Данных тренеров в ответе нет.

## Установка (шаг 2.7а)

Команды — под пользователем с `sudo`. Вместо `trener.example.ru` — настоящий поддомен.

**0. Перед установкой** — проверить, что места хватит, и записать, как работает Planovo, чтобы потом сравнить:

```sh
free -m                      # «available» не меньше 600 МБ, иначе — увеличить тариф (ТЗ 7.4)
df -h /                      # свободно хотя бы 2 ГБ
systemctl --type=service --state=running | grep -iE "planovo|caddy|postgres"
sudo ss -ltnp                # занятые порты: 8090 должен быть свободен, иначе другой в TRENER_PORT и Caddyfile
psql --version; java -version   # PostgreSQL 16 и Java 21 — те же, что у Planovo
```

**1. Поддомен.** У регистратора домена — запись A (и AAAA, если у VPS есть IPv6) для `trener.example.ru` на адрес VPS.

**2. Пользователи и папки.** `trener` — для службы, без входа; `trener-deploy` — для выкладки из GitHub, со своим ключом SSH (не ключ Planovo):

```sh
sudo useradd --system --no-create-home --shell /usr/sbin/nologin trener
sudo useradd --create-home --shell /bin/bash trener-deploy
sudo install -d -o trener-deploy -g trener-deploy -m 755 /opt/trener /opt/trener/releases /opt/trener/bin
sudo -u trener-deploy ssh-keygen -t ed25519 -N "" -f /home/trener-deploy/.ssh/id_ed25519 -C "personalka deploy"
sudo -u trener-deploy sh -c 'cat ~/.ssh/id_ed25519.pub >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys'
```

Выкладке нужны ровно две команды от root — перезапуск службы и её журнал:

```sh
echo 'trener-deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart trener, /usr/bin/journalctl -u trener -n 30 --no-pager' | sudo tee /etc/sudoers.d/trener-deploy
sudo chmod 440 /etc/sudoers.d/trener-deploy && sudo visudo -c
```

**3. База.** Пароль — длинный случайный (`openssl rand -base64 24`), он же пойдёт в `trener.env`:

```sh
sudo -u postgres psql -v password='<пароль>' -f setup.sql
sudo -u postgres psql -c "SHOW listen_addresses"     # localhost — база не видна снаружи
sudo -u postgres psql -c "\l"                        # у баз Planovo нет прав для PUBLIC — тогда trener их не видит
```

Если в `pg_hba.conf` нет общей строки для `127.0.0.1/32` по паролю (`scram-sha-256`), добавить `host trener trener 127.0.0.1/32 scram-sha-256` и `sudo systemctl reload postgresql`.

**4. Настройки.**

```sh
sudo install -d -m 750 -g trener /etc/trener
sudo install -m 640 -g trener trener.env.example /etc/trener/trener.env
sudo nano /etc/trener/trener.env        # пароль базы, поддомен, почта для писем о падениях
```

**5. Службы.** Включить, но не запускать: первый запуск сделает выкладка, когда jar уже будет на месте.

```sh
sudo cp trener.service trener-alert.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable trener
command -v mail || sudo apt install mailutils   # или та отправка почты, которой уже пользуется Planovo
```

**6. Caddy.** Дописать блок из [`Caddyfile`](Caddyfile) в конец общего Caddyfile (обычно `/etc/caddy/Caddyfile`), блок Planovo не трогать:

```sh
sudo caddy validate --config /etc/caddy/Caddyfile && sudo systemctl reload caddy
```

**7. GitHub** (репозиторий → Settings → Secrets and variables → Actions):

| Где | Имя | Значение |
| --- | --- | --- |
| Variables | `TRENER_SSH_HOST` | Адрес VPS |
| Variables | `TRENER_SSH_USER` | `trener-deploy` |
| Variables | `TRENER_PORT` | Только если порт не 8090 |
| Secrets | `TRENER_SSH_KEY` | Закрытый ключ: `sudo cat /home/trener-deploy/.ssh/id_ed25519` |
| Secrets | `TRENER_SSH_KNOWN_HOSTS` | `ssh-keyscan <адрес VPS>` — с компьютера, не с самого VPS |

**8. Первая выкладка.** Actions → «Выкладка сервера» → Run workflow. Проверка:

```sh
curl https://trener.example.ru/health      # ok, версия 0.1.N, схема 1
/opt/trener/bin/server.sh status           # на VPS: какая версия включена
journalctl -u trener -n 50                 # журнал сервера
free -m                                    # сколько памяти осталось
```

И Planovo — работает как раньше: открывается, его служба в списке запущенных, его адрес отвечает.
