#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
KIKmix(.xlsx) 파일을 SBB 마이그레이션 입력 형태(8컬럼 CSV)로 변환한다.

왜 필요한가?
- SBB 법정동 마이그레이션의 "원본 입력"은 KIKmix 엑셀(8컬럼)이며,
  실제 업서트/검증 파이프라인은 CSV로 처리하는 경우가 많다(psql \copy, DBeaver import 등).
- 작업 환경에 pandas/openpyxl 등이 없을 수 있어, 표준 라이브러리(zipfile+xml)만으로 변환한다.

입력(8컬럼)
- 행정동코드, 시도명, 시군구명, 읍면동명, 법정동코드, 동리명, 생성일자, 말소일자

기본 동작
- 첫 번째 시트의 A~H 컬럼을 CSV로 내보낸다.
- 옵션으로 시도명(ctprv_nm, 2번째 컬럼) 필터를 적용할 수 있다.

예시
  python3 scripts/kikmix_xlsx_to_8cols_csv.py \
    --xlsx "KIKmix.20260201(말소코드포함)-인천.xlsx" \
    --ctprv "인천광역시" \
    --out "scripts/out/kikmix_20260201_incheon_8cols.csv"
"""

from __future__ import annotations

import argparse
import csv
import io
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Dict, List, Optional, Tuple


NS_MAIN = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
NS_REL = {"r": "http://schemas.openxmlformats.org/package/2006/relationships"}


def _cell_col_row(cell_ref: str) -> Tuple[str, int]:
	col = "".join([c for c in cell_ref if c.isalpha()])
	row = "".join([c for c in cell_ref if c.isdigit()])
	return col, int(row)


def _col_to_idx(col: str) -> int:
	idx = 0
	for c in col:
		idx = idx * 26 + (ord(c) - ord("A") + 1)
	return idx


def _read_shared_strings(z: zipfile.ZipFile) -> List[str]:
	if "xl/sharedStrings.xml" not in z.namelist():
		return []
	root = ET.fromstring(z.read("xl/sharedStrings.xml"))
	shared: List[str] = []
	for si in root.findall("m:si", NS_MAIN):
		txt = "".join((t.text or "") for t in si.findall(".//m:t", NS_MAIN))
		shared.append(txt)
	return shared


def _resolve_first_sheet_path(z: zipfile.ZipFile) -> str:
	wb = ET.fromstring(z.read("xl/workbook.xml"))
	sheets = wb.findall("m:sheets/m:sheet", NS_MAIN)
	if not sheets:
		raise RuntimeError("No sheets found in workbook.xml")

	first = sheets[0]
	rid = first.attrib.get("{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id")
	if not rid:
		raise RuntimeError("First sheet has no r:id")

	rels = ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))
	rid_to_target = {rel.attrib["Id"]: rel.attrib["Target"] for rel in rels.findall("r:Relationship", NS_REL)}
	target = rid_to_target.get(rid)
	if not target:
		raise RuntimeError(f"Relationship target not found for {rid}")
	return f"xl/{target}"


def _clean(v: Optional[str]) -> str:
	if v is None:
		return ""
	return str(v).replace("\n", " ").strip()


def _iter_rows_a_to_h(
	z: zipfile.ZipFile,
	sheet_path: str,
	shared_strings: List[str],
) -> List[List[str]]:
	"""
	sheet1.xml을 iterparse로 훑어 A~H(1~8)만 추출.
	- 반환: row 단위 리스트(빈 칸은 ''), row 1부터 순서 유지
	"""
	data = z.read(sheet_path)
	rows: List[List[str]] = []

	cur_row_idx: Optional[int] = None
	cur: Dict[int, str] = {}

	# sheetData/row/c/v를 순회하며 행 단위로 flush
	# - end 이벤트에서 row를 확정하는 방식
	for event, elem in ET.iterparse(io.BytesIO(data), events=("start", "end")):
		tag = elem.tag
		if event == "start" and tag.endswith("}row"):
			cur_row_idx = int(elem.attrib.get("r", "0") or "0")
			cur = {}
		elif event == "end" and tag.endswith("}c"):
			if cur_row_idx is None:
				elem.clear()
				continue
			ref = elem.attrib.get("r") or ""
			col, _ = _cell_col_row(ref) if ref else ("", 0)
			col_idx = _col_to_idx(col) if col else 0
			if 1 <= col_idx <= 8:
				v_el = elem.find("m:v", NS_MAIN)
				if v_el is not None and v_el.text is not None:
					val = v_el.text
					if elem.attrib.get("t") == "s":
						try:
							val = shared_strings[int(val)]
						except Exception:
							pass
					cur[col_idx] = val
			elem.clear()
		elif event == "end" and tag.endswith("}row"):
			if cur_row_idx is None:
				elem.clear()
				continue
			row_out = [_clean(cur.get(i, "")) for i in range(1, 9)]
			rows.append(row_out)
			cur_row_idx = None
			cur = {}
			elem.clear()

	return rows


def main(argv: List[str]) -> int:
	parser = argparse.ArgumentParser()
	parser.add_argument("--xlsx", required=True, help="입력 KIKmix xlsx 경로")
	parser.add_argument("--out", required=True, help="출력 CSV 경로")
	parser.add_argument("--ctprv", default="", help="시도명 필터(예: 인천광역시). 비우면 전체 출력")
	# Windows Excel의 UTF-8 오인식(ANSI로 가정)으로 한글이 깨지는 케이스가 많아, 기본값은 BOM 포함으로 둔다.
	parser.add_argument("--bom", dest="bom", action="store_true", help="UTF-8 BOM 포함(기본값)")
	parser.add_argument("--no-bom", dest="bom", action="store_false", help="UTF-8 BOM 미포함")
	parser.set_defaults(bom=True)
	args = parser.parse_args(argv)

	xlsx = Path(args.xlsx)
	if not xlsx.exists():
		print(f"ERROR: xlsx not found: {xlsx}", file=sys.stderr)
		return 2

	out = Path(args.out)
	out.parent.mkdir(parents=True, exist_ok=True)

	ctprv_filter = _clean(args.ctprv)

	with zipfile.ZipFile(xlsx) as z:
		shared = _read_shared_strings(z)
		sheet_path = _resolve_first_sheet_path(z)
		rows = _iter_rows_a_to_h(z, sheet_path, shared)

	# header 확인: 첫 행이 8컬럼 헤더라고 가정하되, 다르면 그대로 출력한다.
	written = 0
	with out.open("w", newline="", encoding="utf-8") as fp:
		if args.bom:
			fp.write("\ufeff")
		w = csv.writer(fp)
		for i, r in enumerate(rows):
			# 필터는 data row에만 적용(헤더는 항상 출력)
			if i == 0:
				w.writerow(r)
				written += 1
				continue
			if ctprv_filter:
				# 2번째 컬럼(시도명)이 일치하는 행만
				if _clean(r[1]) != ctprv_filter:
					continue
			w.writerow(r)
			written += 1

	print(f"OK: wrote {written} rows -> {out}")
	return 0


if __name__ == "__main__":
	sys.exit(main(sys.argv[1:]))
