# Data Migration Tool

A compliance-focused data migration and conversion utility designed to safely transform exported data from desktop applications into web-application ready formats.

## Overview

This project strictly adheres to compliance and legal policies:
- **No Reverse Engineering**: It solely relies on files legitimately exported by the user using the original software's standard export features.
- **No IP Infringement**: Strictly avoids copying UI designs, trademarks, or proprietary code of the source software.

It serves as a secure, transparent middleware to parse various data formats, validate and transform them according to customizable schemas, and output ready-to-import database scripts or JSON files.

## Features

- **Multi-Format Input**: Seamlessly read from `CSV`, `TSV`, `JSON`, and `XML`.
- **Automatic Encoding Detection**: Intelligently handles `UTF-8` (with or without BOM), `Shift-JIS` (CP932), and `EUC-JP` to support files originating from Windows, macOS, and Linux environments.
- **Validation & Transformation**: Built-in customizable field mapping (`FIELD_MAP`) to validate data types (strings, integers, floats, dates, booleans) and enforce rules like max lengths and required fields.
- **Multi-Format Output**:
  - `JSON`
  - SQL `INSERT` statements tailored for **PostgreSQL** or **Oracle Database**.
- **DDL Generation**: Optionally generates `CREATE TABLE` statements based on your defined mapping schema definitions.
- **Robust Error Handling**: Generates detailed error logs for skipped or malformed records without halting the entire conversion process.

*(A Java-based implementation is also available in the `java/` directory.)*

## Requirements

- Python 3.10+ 
- *No external dependencies required* (Standard library only; optionally uses `chardet` for fallback encoding detection if installed).

## Usage

### Basic CLI Example

```bash
python migrate.py input_file.csv -o output.sql -f sql -d postgresql
```

### Options

```text
usage: migrate.py [-h] [-o OUTPUT] [-f {json,sql}] [-d {oracle,postgresql}] 
                  [-e ERROR_LOG] [-t TABLE] [--create-table CREATE_TABLE] [-v] 
                  input

positional arguments:
  input                           Input file path (CSV/TSV/JSON/XML)

options:
  -h, --help                      Show this help message and exit
  -o, --output OUTPUT             Output file path (default: output.json)
  -f, --format {json,sql}         Output format (default: json)
  -d, --dialect {oracle,postgresql} SQL dialect for output (default: postgresql)
  -e, --error-log ERROR_LOG       Error log output path (default: errors.log)
  -t, --table TABLE               Table name for SQL INSERTs (default: imported_data)
  --create-table CREATE_TABLE     Path to write the CREATE TABLE DDL statement
  -v, --verbose                   Enable detailed debug logging
```

## Customization

To adapt the tool for your specific application domain, edit the `FIELD_MAP` dictionary inside `migrate.py`. You can define new columns, data types, validation rules, default values, and target database column types (e.g., `VARCHAR(200)` for PostgreSQL vs `NVARCHAR2(200)` for Oracle).
