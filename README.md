# Data Migration Tool

デスクトップアプリの正規エクスポートデータを、ウェブアプリ用データベース（Oracle / PostgreSQL）にインポート可能な形式へ安全に変換するクロスプラットフォーム対応ツールです。

**Python版** と **Java版** の2言語実装を提供しています。

---

## Table of Contents

- [Features](#features)
- [Compliance Policy](#compliance-policy)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [Quick Start](#quick-start)
- [Usage — Python](#usage--python)
- [Usage — Java](#usage--java)
- [Supported Formats](#supported-formats)
- [Customization](#customization)
- [Sample Data](#sample-data)
- [Error Handling](#error-handling)
- [License](#license)

---

## Features

| 機能 | 説明 |
|------|------|
| **マルチフォーマット入力** | CSV, TSV, JSON, XML を自動判別して読み込み |
| **文字コード自動判定** | UTF-8 (BOM有無), UTF-16, Shift-JIS (CP932/MS932), EUC-JP に対応。Windows/Mac/Linux 混在環境を想定 |
| **バリデーション** | 型チェック（文字列・整数・浮動小数点・日付・真偽値）、必須チェック、最大長チェック |
| **Oracle SQL 出力** | `N'...'` リテラル、`TO_DATE()` 関数、`NUMBER(1)` 真偽値など Oracle 固有構文に対応 |
| **PostgreSQL SQL 出力** | `::DATE` キャスト、`BOOLEAN` 型、`BEGIN/COMMIT` トランザクションに対応 |
| **JSON 出力** | 整形済み JSON ファイルとして出力（API インポート用） |
| **DDL 生成** | `CREATE TABLE` 文を Oracle / PostgreSQL それぞれの方言で自動生成 |
| **エラーログ** | バリデーション失敗行をスキップし、詳細なエラーログを出力 |
| **外部依存なし** | Python: 標準ライブラリのみ / Java: 外部ライブラリ不要 |

---

## Compliance Policy

本ツールはコンプライアンスを最優先に設計されています。

> **1. リバースエンジニアリングの禁止**
> 実行ファイル（.exe, .app, ELF）、ライブラリ（.dll, .dylib, .so）、暗号化された独自ファイルの解析・復号・メモリ読取は **一切行いません**。

> **2. 正規の出力データのみを使用（ホワイトアプローチ）**
> 入力元は、ユーザー自身が元のソフトウェアの **正規エクスポート機能** で出力したファイルのみです。

> **3. UIや名称の模倣禁止**
> 元ソフトウェアの画面デザインのコピーや、商標に抵触する名称のハードコードは行いません。

---

## Architecture

```
data-migration-tool/
├── migrate.py                                  # Python版（単一ファイル）
├── java/
│   └── src/main/java/migration/
│       ├── DataMigrationTool.java              # Java版メイン
│       └── SimpleJsonParser.java               # 軽量JSONパーサー
├── sample_data.csv                             # テスト用サンプル（CSV）
├── sample_data.tsv                             # テスト用サンプル（TSV）
├── sample_data.json                            # テスト用サンプル（JSON）
├── sample_data.xml                             # テスト用サンプル（XML）
└── README.md
```

### 処理フロー

```
┌─────────────────┐     ┌───────────────┐     ┌──────────────────┐     ┌─────────────────┐
│  入力ファイル     │ ──→ │ 文字コード判定  │ ──→ │  パース & 正規化  │ ──→ │  バリデーション   │
│ CSV/TSV/JSON/XML│     │ UTF-8/SJIS/…  │     │  FIELD_MAP適用   │     │  型・必須・長さ   │
└─────────────────┘     └───────────────┘     └──────────────────┘     └────────┬────────┘
                                                                                │
                                                              ┌─────────────────┼─────────────────┐
                                                              ▼                 ▼                 ▼
                                                     ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
                                                     │  JSON出力     │  │ Oracle SQL   │  │ PostgreSQL   │
                                                     │  (API用)     │  │ INSERT文     │  │ INSERT文     │
                                                     └──────────────┘  └──────────────┘  └──────────────┘
                                                                                │
                                                                       ┌──────────────┐
                                                                       │ errors.log   │
                                                                       │ (スキップ行)  │
                                                                       └──────────────┘
```

---

## Requirements

### Python版

- **Python 3.10+**
- 外部ライブラリ不要（`chardet` がインストール済みならフォールバック判定に使用）

### Java版

- **JDK 17+**（record, switch式, テキストブロック使用のため）
- 外部ライブラリ不要

---

## Quick Start

```bash
# リポジトリのクローン
git clone https://github.com/<your-username>/data-migration-tool.git
cd data-migration-tool

# === Python版 ===
# CSV → PostgreSQL INSERT文
python migrate.py sample_data.csv -f sql -d postgresql -t users -o output_pg.sql

# JSON → Oracle INSERT文 + CREATE TABLE文
python migrate.py sample_data.json -f sql -d oracle -t users -o output_ora.sql --create-table ddl_ora.sql

# XML → JSON（API用）
python migrate.py sample_data.xml -f json -o output.json

# === Java版 ===
# コンパイル
javac -encoding UTF-8 -d java/out java/src/main/java/migration/*.java

# TSV → PostgreSQL INSERT文
java -cp java/out migration.DataMigrationTool sample_data.tsv -f sql -d postgresql -t users -o output_pg.sql
```

---

## Usage — Python

```
python migrate.py <入力ファイル> [オプション]
```

### オプション一覧

| オプション | デフォルト | 説明 |
|-----------|-----------|------|
| `-o`, `--output` | `output.json` | 出力ファイルパス |
| `-f`, `--format` | `json` | 出力形式 (`json` \| `sql`) |
| `-d`, `--dialect` | `postgresql` | SQL方言 (`oracle` \| `postgresql`) |
| `-e`, `--error-log` | `errors.log` | エラーログ出力先 |
| `-t`, `--table` | `imported_data` | SQL出力時のテーブル名 |
| `--create-table` | *(なし)* | CREATE TABLE文の出力先パス |
| `-v`, `--verbose` | `false` | 詳細ログを表示 |

### 実行例

```bash
# PostgreSQL用SQL（トランザクション付き）
python migrate.py export.csv -f sql -d postgresql -t users -o import.sql

# Oracle用SQL + DDL
python migrate.py export.xml -f sql -d oracle -t users -o import.sql --create-table ddl.sql

# JSON出力（WebアプリAPI連携用）
python migrate.py export.tsv -f json -o records.json

# 詳細ログ付き
python migrate.py export.csv -f sql -d postgresql -t users -o import.sql -v
```

---

## Usage — Java

```bash
# コンパイル
javac -encoding UTF-8 -d java/out java/src/main/java/migration/*.java

# 実行
java -cp java/out migration.DataMigrationTool <入力ファイル> [オプション]
```

### オプション一覧

| オプション | デフォルト | 説明 |
|-----------|-----------|------|
| `-o` | `output.json` | 出力ファイルパス |
| `-f` | `json` | 出力形式 (`json` \| `sql`) |
| `-d` | `postgresql` | SQL方言 (`oracle` \| `postgresql`) |
| `-e` | `errors.log` | エラーログ出力先 |
| `-t` | `imported_data` | SQL出力時のテーブル名 |
| `--create-table` | *(なし)* | CREATE TABLE文の出力先パス |

---

## Supported Formats

### 入力ファイル

| 形式 | 拡張子 | 備考 |
|------|--------|------|
| CSV | `.csv` | カンマ区切り、クォート対応 |
| TSV | `.tsv` | タブ区切り（区切り文字は自動判定） |
| JSON | `.json` | 配列 `[{...}, ...]` または `{"records": [{...}, ...]}` |
| XML | `.xml` | ルート要素直下の子要素を各レコードとして処理 |

### 文字コード

| エンコーディング | 典型的な環境 |
|-----------------|-------------|
| UTF-8 (BOM有/無) | macOS, Linux, 最近のWindows |
| UTF-16 (LE/BE) | 一部のWindows出力 |
| Shift-JIS (CP932/MS932) | 旧WindowsアプリのCSV出力 |
| EUC-JP | 一部のLinux/Unix環境 |

### 出力形式

#### Oracle SQL

```sql
INSERT INTO users (id, name, email, created_at, is_active)
VALUES (1, N'田中太郎', N'tanaka@example.com', TO_DATE('2024-01-15', 'YYYY-MM-DD'), 1);
COMMIT;
```

#### PostgreSQL SQL

```sql
BEGIN;
INSERT INTO users (id, name, email, created_at, is_active)
VALUES (1, '田中太郎', 'tanaka@example.com', '2024-01-15'::DATE, TRUE);
COMMIT;
```

#### JSON

```json
[
  {
    "id": 1,
    "name": "田中太郎",
    "email": "tanaka@example.com",
    "created_at": "2024-01-15",
    "is_active": true
  }
]
```

---

## Customization

### FIELD_MAP の編集

Python版の `migrate.py` 内、Java版の `DataMigrationTool.java` 内にある `FIELD_MAP` を、実際のDB構造に合わせて編集してください。

```python
# Python版の例
FIELD_MAP = {
    "移行先カラム名": {
        "source":      "元データのカラム名",   # エクスポートファイル側のヘッダー名
        "type":        "str",                # str | int | float | date | bool
        "required":    True,                 # 必須項目か
        "default":     None,                 # 値がない場合のデフォルト
        "max_len":     200,                  # str型の最大文字数（0=制限なし）
        "oracle_type": "NVARCHAR2(200)",     # Oracle用のDDL型
        "pg_type":     "VARCHAR(200)",       # PostgreSQL用のDDL型
    },
}
```

### 日付フォーマットの追加

`convert_value` 関数内の日付パターンリストに追加可能です:

```python
# Python版
for fmt in ("%Y-%m-%d", "%Y/%m/%d", "%Y年%m月%d日", "%m/%d/%Y", "%d/%m/%Y"):
```

### 真偽値の判定

日本語環境でよく使われる表記に対応済みです:

| True | False |
|------|-------|
| `true`, `1`, `yes`, `○`, `はい` | `false`, `0`, `no`, `×`, `いいえ` |

---

## Sample Data

リポジトリに同梱のサンプルデータで動作確認ができます:

| ファイル | 内容 |
|---------|------|
| `sample_data.csv` | CSV形式・7行（うち2行はバリデーションエラー） |
| `sample_data.tsv` | TSV形式・3行 |
| `sample_data.json` | JSON配列形式・3行 |
| `sample_data.xml` | XML形式・3行 |

```bash
# 全形式テスト（Python版）
python migrate.py sample_data.csv  -f sql -d postgresql -t users -o test_csv.sql
python migrate.py sample_data.tsv  -f sql -d oracle     -t users -o test_tsv.sql
python migrate.py sample_data.json -f json -o test_json.json
python migrate.py sample_data.xml  -f json -o test_xml.json
```

---

## Error Handling

バリデーションエラーのある行はスキップされ、処理は中断しません。

### エラーログの例 (`errors.log`)

```
行5: 'name' は必須項目です
行6: 'created_at' の日付形式を認識できません: 'invalid-date'
```

### 実行サマリー

```
18:00:25 [INFO] 入力ファイル読み込み中: sample_data.csv
18:00:25 [INFO] 検出した文字コード: utf-8 (sample_data.csv)
18:00:25 [INFO] 読み込み件数: 7
18:00:25 [WARNING] エラー件数: 2 → errors.log
18:00:25 [INFO] 完了: 全7件 / 成功5件 / スキップ2件
```

---

## License

This project is licensed under the [MIT License](LICENSE).
