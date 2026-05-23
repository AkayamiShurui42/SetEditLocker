# Project Instructions

## Environment Constraints

- **Java Version**: This project currently uses Gradle 8.3, which is incompatible with Java 25 (the version present in the current environment). Attempting to build with Java 25 results in `Unsupported class file major version 69`.
- **Recommended Build Environment**: Use a Java version compatible with Gradle 8.3 (e.g., Java 17 or 20).
- **Workaround**: If Java 25 must be used, the project's Gradle version and Android Gradle Plugin (AGP) would need to be upgraded, which may require significant changes to the build configuration.

## Development Guidelines

- **Memory Management**: Ensure that background tasks (like `java.util.Timer`) are properly managed in Activity lifecycles (e.g., cancelled in `onDestroy`).
- **Permissions**: The app requires `WRITE_SECURE_SETTINGS` for modifying Secure and Global tables. This can be granted via ADB, Root, or Shizuku+.
- **Shizuku+ Integration**: Use the `rikka.shizuku` namespace for all Shizuku related APIs. The app now targets the `rikka.shizuku.plus.permission.API_V23` permission.
- **Root/Shizuku**: The app supports both Root and Shizuku+ for elevated operations.
- **No Legacy Support**: The `moe.shizuku` and `rikka.shizuku` namespaces have been completely removed.
