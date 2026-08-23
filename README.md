# CloverRep

CloverRep — плагин репутации для Paper 26.2. Поддерживает локальное YAML-хранилище, MySQL и интеграцию с PlaceholderAPI.

## Требования

- Paper 26.2 (API `26.2.build.116-stable` или новее в рамках 26.2)
- Java 25
- PlaceholderAPI 2.12.3 — необязательная зависимость для плейсхолдеров

HikariCP и MySQL Connector/J уже включены в итоговый JAR, поэтому устанавливать их отдельно не требуется.

## Установка

1. Соберите проект командой `./gradlew build` (`gradlew.bat build` в Windows) или скачайте готовый JAR из релиза.
2. Поместите `build/libs/CloverRep-1.7.0.jar` в каталог `plugins` сервера.
3. Запустите Paper 26.2 на Java 25.
4. При необходимости настройте `plugins/CloverRep/config.yml` и `messages.yml`.

Для MySQL установите `storage.type: "mysql"` и заполните секцию `mysql`. Если база недоступна при запуске, CloverRep безопасно переключается на локальное YAML-хранилище.

## Команды

- `/rep <+|-> <игрок>` — изменить репутацию игрока.
- `/cloverrep reload` — перезагрузить конфигурацию.
- `/cloverrep status` — показать состояние хранилища и кэшей.
- `/cloverrep checkdb` — проверить MySQL.
- `/cloverrep backup` — создать резервную копию.

## Сборка

Проект использует Gradle Wrapper 9.7.1 и Java toolchain 25:

```text
./gradlew clean build
```

Готовый self-contained JAR создаётся в `build/libs`.
