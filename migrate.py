#!/usr/bin/env python3
"""
データ移行ツール - デスクトップアプリのエクスポートデータ → ウェブアプリ用データ変換

コンプライアンス方針:
- リバースエンジニアリング一切不使用
- ユーザーが正規エクスポート機能で出力したファイルのみを入力とする
- 元ソフトウェアの商標・UI模倣なし

対応入力: CSV, TSV, JSON, XML
対応出力: JSON, Oracle SQL, PostgreSQL SQL
"""

import argparse
import csv
import io
import json
import logging
import sys
from datetime import datetime
from pathlib import Path
from typing import Any


# ============================================================
# 文字コード自動判定
# ============================================================

def detect_encoding(file_path: Path) -> str:
    """ファイルの文字コードを自動判定する。

    Windows環境（Shift-JIS/CP932）とMac/Linux環境（UTF-8）の
    混在を想定し、BOM付きUTF-8も対応する。
    """
    raw = file_path.read_bytes()

    # BOM付きUTF-8
    if raw.startswith(b"\xef\xbb\xbf"):
        return "utf-8-sig"

    # BOM付きUTF-16
    if raw.startswith((b"\xff\xfe", b"\xfe\xff")):
        return "utf-16"

    # UTF-8として試行
    try:
        raw.decode("utf-8")
        return "utf-8"
    except UnicodeDecodeError:
        pass

    # Shift-JIS (CP932) として試行 — Windows日本語環境
    try:
        raw.decode("cp932")
        return "cp932"
    except UnicodeDecodeError:
        pass

    # EUC-JP として試行
    try:
        raw.decode("euc-jp")
        return "euc-jp"
    except UnicodeDecodeError:
        pass

    # chardetが利用可能なら使う
    try:
        import chardet
        result = chardet.detect(raw)
        if result and result["encoding"]:
            return result["encoding"]
    except ImportError:
        pass

    # フォールバック
    return "utf-8"


# ============================================================
# 入力パーサー (CSV / TSV / JSON / XML)
# ============================================================

def read_csv(file_path: Path) -> list[dict[str, str]]:
    """CSV/TSVファイルを読み込み、辞書のリストとして返す。"""
    encoding = detect_encoding(file_path)
    logging.info(f"検出した文字コード: {encoding} ({file_path.name})")

    text = file_path.read_bytes().decode(encoding)

    # 区切り文字の自動判定（CSV/TSV対応）
    sample = text[:4096]
    try:
        dialect = csv.Sniffer().sniff(sample, delimiters=",\t;|")
    except csv.Error:
        dialect = csv.excel

    reader = csv.DictReader(io.StringIO(text), dialect=dialect)
    return list(reader)


def read_json_input(file_path: Path) -> list[dict[str, Any]]:
    """JSONファイルを読み込む。配列またはオブジェクトの配列を期待。"""
    encoding = detect_encoding(file_path)
    text = file_path.read_bytes().decode(encoding)
    data = json.loads(text)
    if isinstance(data, list):
        return data
    if isinstance(data, dict) and "records" in data:
        return data["records"]
    raise ValueError("JSONは配列、または 'records' キーを持つオブジェクトである必要があります")


def read_xml_input(file_path: Path) -> list[dict[str, str]]:
    """XMLファイルを読み込む。各子要素を1レコードとして扱う。"""
    import xml.etree.ElementTree as ET
    encoding = detect_encoding(file_path)
    text = file_path.read_bytes().decode(encoding)
    root = ET.fromstring(text)
    records = []
    for child in root:
        record = {}
        for elem in child:
            record[elem.tag] = elem.text or ""
        records.append(record)
    return records


READERS = {
    ".csv": read_csv,
    ".tsv": read_csv,
    ".json": read_json_input,
    ".xml": read_xml_input,
}


def load_input(file_path: Path) -> list[dict[str, Any]]:
    """拡張子に応じて適切なリーダーで読み込む。"""
    suffix = file_path.suffix.lower()
    reader = READERS.get(suffix)
    if not reader:
        supported = ", ".join(READERS.keys())
        raise ValueError(f"未対応のファイル形式: {suffix} (対応形式: {supported})")
    return reader(file_path)


# ============================================================
# マッピング定義（★ ここをプロジェクトに合わせてカスタマイズ ★）
# ============================================================

# "oracle_type" / "pg_type": DB固有の型名（CREATE TABLE / キャスト用）
FIELD_MAP: dict[str, dict[str, Any]] = {
    "id": {
        "source": "ID",
        "type": "int",
        "required": True,
        "oracle_type": "NUMBER(10)",
        "pg_type": "INTEGER",
    },
    "name": {
        "source": "名前",
        "type": "str",
        "required": True,
        "max_len": 200,
        "oracle_type": "NVARCHAR2(200)",
        "pg_type": "VARCHAR(200)",
    },
    "email": {
        "source": "メールアドレス",
        "type": "str",
        "required": False,
        "max_len": 254,
        "oracle_type": "NVARCHAR2(254)",
        "pg_type": "VARCHAR(254)",
    },
    "created_at": {
        "source": "作成日",
        "type": "date",
        "required": False,
        "oracle_type": "DATE",
        "pg_type": "DATE",
    },
    "is_active": {
        "source": "有効",
        "type": "bool",
        "required": False,
        "default": True,
        "oracle_type": "NUMBER(1)",       # Oracle: 1/0
        "pg_type": "BOOLEAN",             # PostgreSQL: TRUE/FALSE
    },
}


# ============================================================
# バリデーション & 変換
# ============================================================

def convert_value(value: str | None, field_name: str, spec: dict) -> Any:
    """1つの値を仕様に基づいて変換・検証する。"""
    field_type = spec.get("type", "str")

    if value is None or (isinstance(value, str) and value.strip() == ""):
        if spec.get("required"):
            raise ValueError(f"'{field_name}' は必須項目です")
        return spec.get("default")

    value = value.strip()

    if field_type == "str":
        max_len = spec.get("max_len")
        if max_len and len(value) > max_len:
            raise ValueError(
                f"'{field_name}' が最大長 {max_len} を超えています ({len(value)}文字)"
            )
        return value

    if field_type == "int":
        try:
            return int(value.replace(",", ""))
        except ValueError:
            raise ValueError(f"'{field_name}' を整数に変換できません: '{value}'")

    if field_type == "float":
        try:
            return float(value.replace(",", ""))
        except ValueError:
            raise ValueError(f"'{field_name}' を数値に変換できません: '{value}'")

    if field_type == "date":
        for fmt in ("%Y-%m-%d", "%Y/%m/%d", "%Y年%m月%d日", "%m/%d/%Y", "%d/%m/%Y"):
            try:
                return datetime.strptime(value, fmt).strftime("%Y-%m-%d")
            except ValueError:
                continue
        raise ValueError(f"'{field_name}' の日付形式を認識できません: '{value}'")

    if field_type == "bool":
        if value.lower() in ("true", "1", "yes", "○", "はい"):
            return True
        if value.lower() in ("false", "0", "no", "×", "いいえ", ""):
            return False
        raise ValueError(f"'{field_name}' を真偽値に変換できません: '{value}'")

    return value


def transform_record(
    row: dict[str, Any], row_index: int
) -> tuple[dict[str, Any] | None, list[str]]:
    """1行を変換する。成功時は変換後dictを、失敗時はNoneとエラーリストを返す。"""
    errors: list[str] = []
    result: dict[str, Any] = {}

    for dest_field, spec in FIELD_MAP.items():
        source_col = spec.get("source")
        raw_value = row.get(source_col) if source_col else None
        try:
            result[dest_field] = convert_value(raw_value, dest_field, spec)
        except ValueError as e:
            errors.append(f"行{row_index}: {e}")

    if errors:
        return None, errors
    return result, []


# ============================================================
# 出力フォーマッター
# ============================================================

def output_json(records: list[dict], output_path: Path) -> None:
    """変換済みレコードをJSONファイルとして出力する。"""
    output_path.write_text(
        json.dumps(records, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    logging.info(f"JSON出力: {output_path} ({len(records)}件)")


def _format_sql_value(v: Any, field_name: str, dialect: str) -> str:
    """値を指定DB方言のSQLリテラルに変換する。"""
    if v is None:
        return "NULL"

    spec = FIELD_MAP.get(field_name, {})
    field_type = spec.get("type", "str")

    # 真偽値: Oracle=1/0, PostgreSQL=TRUE/FALSE
    if field_type == "bool" or isinstance(v, bool):
        if dialect == "oracle":
            return "1" if v else "0"
        else:
            return "TRUE" if v else "FALSE"

    # 数値
    if isinstance(v, (int, float)):
        return str(v)

    # 日付: Oracle=TO_DATE, PostgreSQL='YYYY-MM-DD'::DATE
    if field_type == "date":
        escaped = str(v).replace("'", "''")
        if dialect == "oracle":
            return f"TO_DATE('{escaped}', 'YYYY-MM-DD')"
        else:
            return f"'{escaped}'::DATE"

    # 文字列
    escaped = str(v).replace("'", "''")
    # Oracle: NVARCHARリテラルにはN接頭辞
    if dialect == "oracle":
        return f"N'{escaped}'"
    return f"'{escaped}'"


def output_sql(
    records: list[dict],
    output_path: Path,
    table_name: str,
    dialect: str,
) -> None:
    """変換済みレコードをDB方言に応じたSQLインサート文として出力する。"""
    if not records:
        output_path.write_text("-- No records\n", encoding="utf-8")
        return

    lines: list[str] = []
    columns = list(records[0].keys())
    col_str = ", ".join(columns)

    # ヘッダーコメント
    db_label = "Oracle" if dialect == "oracle" else "PostgreSQL"
    lines.append(f"-- データ移行ツール生成 ({db_label})")
    lines.append(f"-- 生成日時: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append(f"-- レコード数: {len(records)}")
    lines.append("")

    # PostgreSQL: トランザクション囲み
    if dialect == "postgresql":
        lines.append("BEGIN;")
        lines.append("")

    for rec in records:
        values = []
        for col in columns:
            values.append(_format_sql_value(rec[col], col, dialect))
        val_str = ", ".join(values)
        lines.append(f"INSERT INTO {table_name} ({col_str}) VALUES ({val_str});")

    if dialect == "postgresql":
        lines.append("")
        lines.append("COMMIT;")

    # Oracle: 明示COMMIT
    if dialect == "oracle":
        lines.append("")
        lines.append("COMMIT;")

    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    logging.info(f"SQL出力 ({db_label}): {output_path} ({len(records)}件)")


def output_create_table(
    output_path: Path, table_name: str, dialect: str
) -> None:
    """CREATE TABLE文を出力する（参考用）。"""
    type_key = "oracle_type" if dialect == "oracle" else "pg_type"
    db_label = "Oracle" if dialect == "oracle" else "PostgreSQL"

    lines = [
        f"-- CREATE TABLE ({db_label})",
        f"CREATE TABLE {table_name} (",
    ]
    col_defs = []
    for col_name, spec in FIELD_MAP.items():
        col_type = spec.get(type_key, "VARCHAR(255)")
        nullable = "" if spec.get("required") else " NULL"
        not_null = " NOT NULL" if spec.get("required") else ""
        col_defs.append(f"    {col_name} {col_type}{not_null}")

    lines.append(",\n".join(col_defs))
    lines.append(");")

    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    logging.info(f"CREATE TABLE出力 ({db_label}): {output_path}")


# ============================================================
# メイン処理
# ============================================================

def run(
    input_path: Path,
    output_path: Path,
    output_format: str,
    error_log_path: Path,
    table_name: str,
    dialect: str,
    create_table_path: Path | None,
) -> None:
    """移行処理のメインエントリポイント。"""

    logging.info(f"入力ファイル読み込み中: {input_path}")
    rows = load_input(input_path)
    logging.info(f"読み込み件数: {len(rows)}")

    converted: list[dict[str, Any]] = []
    all_errors: list[str] = []

    for i, row in enumerate(rows, start=1):
        record, errors = transform_record(row, i)
        if record is not None:
            converted.append(record)
        else:
            all_errors.extend(errors)

    # 成功データ出力
    if output_format == "json":
        output_json(converted, output_path)
    else:
        output_sql(converted, output_path, table_name, dialect)

    # CREATE TABLE文出力（オプション）
    if create_table_path:
        output_create_table(create_table_path, table_name, dialect)

    # エラーログ出力
    if all_errors:
        error_log_path.write_text("\n".join(all_errors) + "\n", encoding="utf-8")
        logging.warning(f"エラー件数: {len(all_errors)} → {error_log_path}")
    else:
        logging.info("エラーなし")

    # サマリー
    total = len(rows)
    ok = len(converted)
    ng = total - ok
    logging.info(f"完了: 全{total}件 / 成功{ok}件 / スキップ{ng}件")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="データ移行ツール — エクスポートデータをウェブアプリ用DBに変換"
    )
    parser.add_argument("input", help="入力ファイルパス (CSV/TSV/JSON/XML)")
    parser.add_argument(
        "-o", "--output",
        default="output.json",
        help="出力ファイルパス (デフォルト: output.json)",
    )
    parser.add_argument(
        "-f", "--format",
        choices=["json", "sql"],
        default="json",
        help="出力形式 (デフォルト: json)",
    )
    parser.add_argument(
        "-d", "--dialect",
        choices=["oracle", "postgresql"],
        default="postgresql",
        help="SQL方言 (デフォルト: postgresql)",
    )
    parser.add_argument(
        "-e", "--error-log",
        default="errors.log",
        help="エラーログ出力先 (デフォルト: errors.log)",
    )
    parser.add_argument(
        "-t", "--table",
        default="imported_data",
        help="SQL出力時のテーブル名 (デフォルト: imported_data)",
    )
    parser.add_argument(
        "--create-table",
        default=None,
        help="CREATE TABLE文の出力先 (省略時は出力しない)",
    )
    parser.add_argument(
        "-v", "--verbose",
        action="store_true",
        help="詳細ログを表示",
    )

    args = parser.parse_args()

    log_level = logging.DEBUG if args.verbose else logging.INFO
    logging.basicConfig(
        level=log_level,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )

    input_path = Path(args.input)
    if not input_path.exists():
        logging.error(f"入力ファイルが見つかりません: {input_path}")
        sys.exit(1)

    create_table_path = Path(args.create_table) if args.create_table else None

    run(
        input_path=input_path,
        output_path=Path(args.output),
        output_format=args.format,
        error_log_path=Path(args.error_log),
        table_name=args.table,
        dialect=args.dialect,
        create_table_path=create_table_path,
    )


if __name__ == "__main__":
    main()
