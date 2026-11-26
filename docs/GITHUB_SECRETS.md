# GitHub Secrets для релизного workflow

Workflow `.github/workflows/release.yml` подписывает APK в CI, материализуя keystore в
`~/.glazegram/secrets.env` — тот же файл, что читает `app/build.gradle.kts` на локальной машине.
Сами ключи в репозитории не хранятся.

## Какие секреты нужны

| Secret | Назначение | Обязательность |
|---|---|---|
| `KASANE_KEYSTORE_BASE64` | release-keystore, base64 | **обязателен для stable** — без него android-джоб падает и stable-релиз не публикуется |
| `KASANE_KEYSTORE_PASSWORD` | storePassword | вместе с keystore |
| `KASANE_KEY_ALIAS` | keyAlias | вместе с keystore |
| `KASANE_KEY_PASSWORD` | keyPassword | вместе с keystore |
| `KASANE_NIGHTLY_KEYSTORE_BASE64` | отдельный nightly-keystore, base64 | опционально; без него nightly падает на stable-ключ, а без обоих — на эфемерный CI-ключ |
| `KASANE_NIGHTLY_KEYSTORE_PASSWORD` | storePassword | вместе с nightly-keystore |
| `KASANE_NIGHTLY_KEY_ALIAS` | keyAlias | вместе с nightly-keystore |
| `KASANE_NIGHTLY_KEY_PASSWORD` | keyPassword | вместе с nightly-keystore |

## Подпись nightly — приоритет

1. `KASANE_NIGHTLY_KEYSTORE_*` заданы → nightly подписывается **постоянным** nightly-ключом —
   nightly-сборки обновляют друг друга in-place (нужно для OTA-канала nightly).
2. Иначе, если заданы `KASANE_KEYSTORE_*` → nightly подписывается release-ключом (тоже
   постоянная подпись).
3. Иначе → **эфемерный CI-ключ**: каждая сборка имеет другую подпись, обновление поверх
   предыдущего CI-nightly невозможно (ассет помечается суффиксом `-ci`, предупреждение
   попадает в notes релиза).

## Генерация nightly-keystore

Отдельный ключ для nightly изолирует частые сборки от release-ключа: компрометация
nightly-ключа не ставит под угрозу stable-обновления.

```bash
keytool -genkeypair -v \
  -keystore kasane-nightly.keystore \
  -alias kasane-nightly \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Kasane Nightly, O=Kasane"
```

(`validity` ~30 лет — для nightly-ключа норма; короткая валидность сломает обновления.)

## Загрузка в secrets

```bash
base64 -w0 kasane-nightly.keystore > nightly.b64   # без переносов строк
```

Дальше — **Settings → Secrets and variables → Actions → New repository secret**:

- `KASANE_NIGHTLY_KEYSTORE_BASE64` ← содержимое `nightly.b64`
- `KASANE_NIGHTLY_KEYSTORE_PASSWORD` ← storePassword из генерации
- `KASANE_NIGHTLY_KEY_ALIAS` ← `kasane-nightly`
- `KASANE_NIGHTLY_KEY_PASSWORD` ← keyPassword

Аналогично для stable: `base64 -w0 release.keystore`, четыре секрета `KASANE_KEYSTORE_*`.
**Stable keystore — это тот же ключ, что в `~/.glazegram/release.keystore` локально** —
иначе CI-stable не совместится по подписи с локальными сборками и установленными копиями.

Храните `.keystore` и пароли вне репозитория; `nightly.b64` после загрузки удалить.

## Проверка без прогона

- Без любых секретов: `workflow_dispatch` с `channel=nightly` соберёт `-ci`-APK с пометкой.
- С `KASANE_NIGHTLY_*`: nightly-APK подписывается постоянным ключом — в notes релиза
  строка «signed with the dedicated nightly key».
- С `KASANE_KEYSTORE_*`: stable-релиз публикуется; nightly без своего ключа берёт release-подпись.
