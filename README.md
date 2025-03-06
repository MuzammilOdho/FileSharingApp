# FileShare 📁🚀

A modern Java-based file-sharing application with a sleek GUI for fast, secure, and reliable peer-to-peer file transfers.

---

## Features ✨

- **Parallel Chunk Transfers**: Files are divided into 64MB chunks and sent concurrently for faster transfers.
- **Zero-Copy Optimization**: Uses `FileChannel.transferTo` for high-speed, memory-efficient transfers.
- **Modern FlatLaf GUI**: Sleek and responsive UI with dark/light themes and high-DPI support.
- **Network Discovery**: Automatically detects peers on the same network via UDP broadcasts.
- **Drag-and-Drop**: Intuitive file selection using drag-and-drop functionality.
- **Progress Tracking**: Real-time transfer statistics, including speed and estimated time remaining.

---

## Tech Stack 💻

- **Java 17+**: Core programming language.
- **FlatLaf**: Modern Swing look-and-feel for the GUI.
- **Java NIO**: High-performance networking and file I/O.
- **Maven**: Build and dependency management.




