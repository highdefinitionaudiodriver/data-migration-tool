# Data Migration Tool

デスクトップアプリの正規エクスポートデータを、ウェブアプリ用データベース（Oracle / PostgreSQL）にインポート可能な形式へ安全に変換するクロスプラットフォーム対応ツールです。

**Python版** と **Java版** の2言語実装を提供。**外部設定ファイル（JSON）でマッピングを定義**するため、コード変更なしに任意のテーブルに対応できます。

> レガシーシステム刷新や業務アプリ移行の初期段階で、既存データの項目対応・変換エラー・移行リスクを可視化するための診断ツールとして活用できます。

---

## 🎯 これは何？（30秒で）

- **誰のため**：デスクトップ業務アプリの Web 化を進めている情シス／レガシーアプリ刷新の SI ベンダー
- **何が解決される**：エクスポート CSV をターゲット DB（Oracle / PostgreSQL）に投入可能な形式へ **JSON マッピングのみで安全変換**。コード変更なしに任意のテーブル構造へ対応
- **なぜ既存ツールではダメか**：ETL ツールは大規模・高額・学習コスト大。小〜中規模の業務データ移行には過剰。本ツールは **Python 版 + Java 版** を提供し、現場の言語スタックに合わせて選べる
- **使う条件**：Python 3.10+ または Java 17+ / Windows・macOS・Linux

## 💰 想定ユースケース・価格帯

| 用途 | 形態 |
|---|---|
| OSS としての利用（自社内 PoC・小規模移行） | 無料（MIT） |
| 業務アプリ刷新時の **データ変換・移行レポート受託** | 応相談 |
| 業界特化の追加バリデーション・マッピング開発 | 個別見積もり |

---

## Table of Contents

- [Features](#features)
- [Business Use](#business-use)
- [Compliance Policy](#compliance-policy)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [Quick Start](#quick-start)
- [Configuration File](#configuration-file)
- [Usage — Python](#usage--python)
- [Usage — Java](#usage--java)
- [Supported Formats](#supported-formats)
- [Error Handling](#error-handling)
- [License](#license)

---

## Features

| 機能 | 説明 |
|------|------|
| **設定ファイル駆動** | `mapping_config.json` でマッピングを定義。コード変更不要で任意のテーブルに対応 |
| **マルチフォーマット入力** | CSV, TSV, JSON, XML を自動判別して読み込み |
| **文字コード自動判定** | UTF-8 (BOM有無), UTF-16, Shift-JIS (CP932/MS932), EUC-JP に対応 |
| **バリデーション** | 型チェック（文字列・整数・浮動小数点・日付・真偽値）、必須チェック、最大長チェック |
| **Oracle SQL 出力** | `N'...'` リテラル、`TO_DATE()` 関数、`NUMBER(1)` 真偽値など Oracle 固有構文 |
| **PostgreSQL SQL 出力** | `::DATE` キャスト、`BOOLEAN` 型、`BEGIN/COMMIT` トランザクション |
| **JSON 出力** | 整形済み JSON ファイル（API インポート用） |
| **DDL 生成** | `CREATE TABLE` 文を Oracle / PostgreSQL それぞれの方言で自動生成 |
| **エラーログ** | バリデーション失敗行をスキップし、詳細なエラーログを別ファイルに出力 |
| **外部依存なし** | Python: 標準ライブラリのみ / Java: 外部ライブラリ不要 |

---

## Business Use

- 旧デスクトップアプリからWebアプリ・クラウドDBへの移行前診断
- CSV / TSV / JSON / XML の項目対応、型不一致、欠損データの洗い出し
- Oracle / PostgreSQL への投入SQLとDDLのたたき台生成
- 移行見積もりに必要なエラー件数・手修正ポイントの可視化

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
├── mapping_config.json                         # マッピング設定ファイル（★ ユーザー編集）
├── migrate.py                                  # Python版
├── java/src/main/java/migration/
│   ├── DataMigrationTool.java                  # Java版メイン
│   └── SimpleJsonParser.java                   # 軽量JSONパーサー
├── sample_employee.csv                         # テスト用サンプル（CSV）
├── sample_employee.json                        # テスト用サンプル（JSON）
├── sample_data.csv / .tsv / .json / .xml       # 汎用テスト用サンプル
└── README.md
```

### 処理フロー

```
┌──────────────────┐     ┌──────────────────┐
│ mapping_config   │     │  入力ファイル      │
│    .json         │     │ CSV/TSV/JSON/XML  │
└───────┬──────────┘     └────────┬──────────┘
        │                         │
        ▼                         ▼
┌──────────────────────────────────────────────┐
│              マッピングエンジン                 │
│  ┌────────────┐  ┌──────────┐  ┌───────────┐ │
│  │ 文字コード  │→│ パース &  │→│ バリデー   │ │
│  │ 自動判定   │  │ 正規化   │  │ ション    │ │
│  └────────────┘  └──────────┘  └─────┬─────┘ │
└──────────────────────────────────────┼───────┘
                      ┌────────────────┼────────────────┐
                      ▼                ▼                ▼
             ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
             │  JSON 出力   │  │ Oracle SQL   │  │ PostgreSQL   │
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
- **JDK 17+**（record, switch式使用のため）
- 外部ライブラリ不要

---

## Quick Start

```bash
git clone https://github.com/<your-username>/data-migration-tool.git
cd data-migration-tool

# 1. mapping_config.json を移行先DB仕様に合わせて編集
# 2. 実行

# === Python版 ===
python migrate.py export.csv -c mapping_config.json -f sql -d postgresql -o import.sql

# === Java版 ===
javac -encoding UTF-8 -d java/out java/src/main/java/migration/*.java
java -cp java/out migration.DataMigrationTool export.csv -c mapping_config.json -f sql -d oracle -o import.sql
```

---

## Configuration File

`mapping_config.json` でマッピングを定義します。**コードを一切変更せず**、このファイルを書き換えるだけで任意のテーブルに対応できます。

### 設定ファイルの構造

```json
{
  "table_name": "m_employee",

  "fields": {
    "DBカラム名": {
      "source":      "元データのヘッダー名",
      "type":        "str | int | float | date | bool",
      "required":    true,
      "max_len":     100,
      "default":     null,
      "oracle_type": "NVARCHAR2(100)",
      "pg_type":     "VARCHAR(100)"
    }
  }
}
```

### 各プロパティの説明

| プロパティ | 必須 | 型 | 説明 |
|-----------|------|-----|------|
| `table_name` | - | string | SQL出力時のテーブル名（`-t` 引数で上書き可能） |
| `fields` | 必須 | object | フィールドマッピング定義 |

#### fields 内の各フィールド

| プロパティ | 必須 | デフォルト | 説明 |
|-----------|------|-----------|------|
| `source` | 必須 | - | 元データ（CSV等）のカラム名/ヘッダー名 |
| `type` | - | `"str"` | データ型: `str`, `int`, `float`, `date`, `bool` |
| `required` | - | `false` | `true` = NOT NULL（空値でエラー） |
| `max_len` | - | 制限なし | str型の最大文字数 |
| `default` | - | `null` | 値が空の場合のデフォルト値 |
| `oracle_type` | - | `VARCHAR2(255)` | Oracle用のCREATE TABLE型名 |
| `pg_type` | - | `VARCHAR(255)` | PostgreSQL用のCREATE TABLE型名 |

### サンプル設定ファイル（同梱）

```json
{
  "table_name": "m_employee",

  "fields": {
    "emp_id": {
      "source": "社員番号",
      "type": "str",
      "required": true,
      "max_len": 20,
      "oracle_type": "VARCHAR2(20)",
      "pg_type": "VARCHAR(20)"
    },
    "full_name": {
      "source": "氏名",
      "type": "str",
      "required": true,
      "max_len": 100,
      "oracle_type": "NVARCHAR2(100)",
      "pg_type": "VARCHAR(100)"
    },
    "hire_date": {
      "source": "入社年月日",
      "type": "date",
      "required": false,
      "oracle_type": "DATE",
      "pg_type": "DATE"
    },
    "salary": {
      "source": "基本給",
      "type": "int",
      "required": false,
      "default": 0,
      "oracle_type": "NUMBER(10)",
      "pg_type": "INTEGER"
    },
    "is_active": {
      "source": "在籍フラグ",
      "type": "bool",
      "required": false,
      "default": true,
      "oracle_type": "NUMBER(1)",
      "pg_type": "BOOLEAN"
    }
  }
}
```

### 別のテーブルを移行するには？

設定ファイルを複数用意するだけです：

```bash
# 社員テーブル
python migrate.py employees.csv -c config_employee.json -f sql -d postgresql -o emp.sql

# 商品テーブル
python migrate.py products.csv -c config_product.json -f sql -d oracle -o prod.sql

# 受注テーブル
python migrate.py orders.xml -c config_order.json -f json -o orders.json
```

---

## Usage — Python

```
python migrate.py <入力ファイル> -c <設定ファイル> [オプション]
```

### オプション一覧

| オプション | 必須 | デフォルト | 説明 |
|-----------|------|-----------|------|
| `-c`, `--config` | **必須** | - | マッピング設定ファイルパス (JSON) |
| `-o`, `--output` | - | `output.json` | 出力ファイルパス |
| `-f`, `--format` | - | `json` | 出力形式 (`json` \| `sql`) |
| `-d`, `--dialect` | - | `postgresql` | SQL方言 (`oracle` \| `postgresql`) |
| `-e`, `--error-log` | - | `errors.log` | エラーログ出力先 |
| `-t`, `--table` | - | 設定ファイルの値 | テーブル名（設定ファイルの `table_name` を上書き） |
| `--create-table` | - | *(なし)* | CREATE TABLE文の出力先パス |
| `-v`, `--verbose` | - | `false` | 詳細ログを表示 |

### 実行例

```bash
# PostgreSQL用INSERT文 + DDL
python migrate.py export.csv -c mapping_config.json -f sql -d postgresql -t users -o import.sql --create-table ddl.sql

# Oracle用INSERT文
python migrate.py export.xml -c mapping_config.json -f sql -d oracle -o import.sql

# JSON出力（WebアプリAPI用）
python migrate.py export.tsv -c mapping_config.json -f json -o records.json

# テーブル名を引数で上書き
python migrate.py export.csv -c mapping_config.json -f sql -d postgresql -t custom_table -o import.sql
```

---

## Usage — Java

```bash
# コンパイル
javac -encoding UTF-8 -d java/out java/src/main/java/migration/*.java

# 実行
java -cp java/out migration.DataMigrationTool <入力ファイル> -c <設定ファイル> [オプション]
```

### オプション一覧

| オプション | 必須 | デフォルト | 説明 |
|-----------|------|-----------|------|
| `-c` | **必須** | - | マッピング設定ファイルパス (JSON) |
| `-o` | - | `output.json` | 出力ファイルパス |
| `-f` | - | `json` | 出力形式 (`json` \| `sql`) |
| `-d` | - | `postgresql` | SQL方言 (`oracle` \| `postgresql`) |
| `-e` | - | `errors.log` | エラーログ出力先 |
| `-t` | - | 設定ファイルの値 | テーブル名 |
| `--create-table` | - | *(なし)* | CREATE TABLE文の出力先パス |

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

### 出力例

#### Oracle SQL

```sql
INSERT INTO m_employee (emp_id, full_name, hire_date, salary, is_active)
VALUES (N'EMP001', N'田中太郎', TO_DATE('2020-04-01', 'YYYY-MM-DD'), 350000, 1);
COMMIT;
```

#### PostgreSQL SQL

```sql
BEGIN;
INSERT INTO m_employee (emp_id, full_name, hire_date, salary, is_active)
VALUES ('EMP001', '田中太郎', '2020-04-01'::DATE, 350000, TRUE);
COMMIT;
```

#### JSON

```json
[
  {
    "emp_id": "EMP001",
    "full_name": "田中太郎",
    "hire_date": "2020-04-01",
    "salary": 350000,
    "is_active": true
  }
]
```

### 自動変換対応

#### 日付形式

| 入力例 | 変換結果 |
|--------|---------|
| `2024-01-15` | `2024-01-15` |
| `2024/01/15` | `2024-01-15` |
| `2024年01月15日` | `2024-01-15` |
| `01/15/2024` | `2024-01-15` |

#### 真偽値

| True として認識 | False として認識 |
|----------------|-----------------|
| `true`, `1`, `yes`, `○`, `はい` | `false`, `0`, `no`, `×`, `いいえ` |

---

## Error Handling

バリデーションエラーのある行は **スキップ** され、処理全体は中断しません。

### エラーログの例 (`errors.log`)

```
行5: 'full_name' は必須項目です
行8: 'hire_date' の日付形式を認識できません: 'invalid-date'
行12: 'email' が最大長 254 を超えています (300文字)
```

### 実行サマリー

```
00:18:11 [INFO] 設定ファイル読み込み完了: mapping_config.json (テーブル: m_employee, フィールド数: 8)
00:18:11 [INFO] 入力ファイル読み込み中: sample_employee.csv
00:18:11 [INFO] 検出した文字コード: utf-8 (sample_employee.csv)
00:18:11 [INFO] 読み込み件数: 5
00:18:11 [INFO] SQL出力 (PostgreSQL): import.sql (4件)
00:18:11 [WARNING] エラー件数: 1 → errors.log
00:18:11 [INFO] 完了: 全5件 / 成功4件 / スキップ1件
```

---

## License

This project is licensed under the [MIT License](LICENSE).

---

## 🤝 商用利用・カスタマイズ依頼

- 個人・社内利用は無料（MIT ライセンス）
- 法人・自治体・SI 向け導入支援、カスタマイズ、診断レポート受託は応相談
- 連絡先：highdefinitionaudiodriver@gmail.com
