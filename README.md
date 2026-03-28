# SNI Pinger

Android-приложение для проверки доступности сервера по IP-адресу с использованием заданного SNI (Server Name Indication). Полезно при настройке VPN, обходе блокировок и диагностике TLS-соединений.

## Возможности

- **Два режима проверки** — «Свой SNI» (вручную) и «Whitelist» (по списку)
- **Комплексная диагностика** — проверка TCP, TLS, HTTP и DNS
- **Подробные результаты** — разбивка по секциям с временем отклика
- **RAW Output** — полный вывод результатов с возможностью копирования
- **Material Design 3** — поддержка тёмной темы

## Скриншоты

> *(будут добавлены)*

## Требования

- Android 7.0+ (API 24+)
- Разрешения: `INTERNET`, `ACCESS_NETWORK_STATE`

## Сборка

```bash
git clone https://github.com/begugla0/sni-pinger.git
cd sni-pinger
./gradlew assembleDebug
```

APK будет в `app/build/outputs/apk/debug/`.

## Использование

1. Введите **IP-адрес** сервера
2. Введите **SNI hostname** (например, `vk.com`)
3. Нажмите **«Начать проверку»**
4. Просмотрите результаты по секциям: TCP / TLS / HTTP / DNS

## Структура проекта

```
app/src/main/
├── java/        # Kotlin-исходники
├── res/
│   ├── layout/  # UI (activity_main, item_result_row, item_result_section)
│   └── values/  # Цвета, стили, строки
└── AndroidManifest.xml
```

## Лицензия

Этот проект распространяется под лицензией **Business Source License 1.1**.  
Коммерческое использование и продажа запрещены до **2031-03-29**, после чего лицензия автоматически становится GPL v2.0.  
Подробнее — в файле [LICENSE](./LICENSE).

## Автор

[begugla0](https://github.com/begugla0)
