# Eliterads Pro

Eliterads Pro is a Java desktop application for radiology workstation tasks. It includes tools for capturing screen images and extracting text with OCR, preparing reports, managing records, and exporting data.

## Features

- Capture screen regions for names or images, with OCR text extraction.
- Prepare reports and export them to PDF.
- Create and manage records in a local SQLite database.
- Export data to Excel and import a database.
- Work with supported archive formats and access PACS-related tools.
- Configure workstation settings and application appearance.

## Requirements

- Java Development Kit (JDK) 25
- Maven
- Microsoft Word for DOCX-to-PDF conversion

For Windows MSI packaging, install WiX Toolset 3.x and ensure `candle.exe`, `light.exe`, and Java's `jpackage.exe` are available on `PATH`.

## Build and run

From the project directory:

```shell
mvn clean test
mvn javafx:run
```

To build the application JAR:

```shell
mvn package
```

On Windows, create an MSI installer with:

```powershell
.\scripts\package-windows-msi.ps1
```

To create a Windows app image instead:

```powershell
.\scripts\package-windows-msi.ps1 app-image
```

## Data and privacy

The application stores records in a local SQLite database. Database files and workstation configuration may contain sensitive or patient information. Keep them out of source control and do not publish them to GitHub. Use synthetic data when developing or sharing examples, and follow your organization's privacy and data-handling requirements.

## Project structure

- `src/main/java/` — application source code
- `src/main/resources/` — styles and application resources
- `src/test/java/` — automated tests
- `scripts/` — Windows packaging script
- `pom.xml` — Maven build and dependency configuration
