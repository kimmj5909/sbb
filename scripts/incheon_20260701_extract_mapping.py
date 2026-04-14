#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
인천광역시 행정동/법정동 변경 상세내역(xlsx)에서 "법정동코드 재배치 매핑"을 추출한다.

배경
- SBB 법정동 마이그레이션은 MOIS KIKmix(8컬럼) 기반 업서트를 기본으로 한다.
- 이번 인천 개편(중구/동구/서구 → 제물포구/영종구/서구/검단구)은 "변경 상세내역" 엑셀에
  기존/변경후 관할구역(법정동) 코드가 5자리 꼬리값(예: 10100) 형태로 정리되어 있다.
- 따라서:
  1) 이 엑셀에서 old/new (시군구코드 5자리) + (법정동꼬리 5자리) 를 결합해 10자리 법정동코드를 만들고,
  2) new_legal_dong_cd(신코드) → old_legal_dong_cd(구코드) 매핑을 CSV로 산출해
     - past_legal_dong_cd 보정
     - 주소/연계 데이터 치환(legal_dong_cd 기반)
    에 활용한다.

제약
- 실행 환경에 pandas/openpyxl 등이 없을 수 있어, 표준 라이브러리(zipfile+xml)만 사용한다.
- 엑셀의 병합 셀로 인해 A/B/E/F 컬럼(기관명/행정동)이 빈칸으로 내려오는 행이 많다.
  본 스크립트는 직전 행 값을 fill-down(상속)하여 레코드를 완성한다.

입력
- 행정기관(행정동) 및 관할구역(법정동) 변경 상세내역(인천광역시).xlsx (프로젝트 루트)

출력
- scripts/out/incheon_20260701_admin_bjd_mapping.csv
- scripts/out/incheon_20260701_affected_sgng_codes.csv
- scripts/out/incheon_20260701_delta_8cols.csv (선택: SBB 마이그레이션 입력 형태 8컬럼 델타)

실행
  python3 scripts/incheon_20260701_extract_mapping.py
  python3 scripts/incheon_20260701_extract_mapping.py --xlsx "<path>" --out-dir scripts/out
"""

from __future__ import annotations

import argparse
import csv
import os
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple


NS_MAIN = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
NS_REL = {"r": "http://schemas.openxmlformats.org/package/2006/relationships"}


def _cell_col_row(cell_ref: str) -> Tuple[str, int]:
	col = "".join([c for c in cell_ref if c.isalpha()])
	row = "".join([c for c in cell_ref if c.isdigit()])
	return col, int(row)


def _read_shared_strings(z: zipfile.ZipFile) -> List[str]:
	if "xl/sharedStrings.xml" not in z.namelist():
		return []
	root = ET.fromstring(z.read("xl/sharedStrings.xml"))
	shared: List[str] = []
	for si in root.findall("m:si", NS_MAIN):
		# 하나의 셀에 여러 run(<r>)이 있을 수 있어 모든 <t>를 concat한다.
		txt = "".join((t.text or "") for t in si.findall(".//m:t", NS_MAIN))
		shared.append(txt)
	return shared


def _resolve_first_sheet_target(z: zipfile.ZipFile) -> str:
	"""
	workbook.xml 기준 첫 시트의 실제 xml 경로(worksheets/sheetN.xml)를 리턴.
	이 파일은 단일 시트만 있다고 가정하지 않고, workbook rels를 통해 안전하게 찾는다.
	"""
	wb = ET.fromstring(z.read("xl/workbook.xml"))
	sheets = wb.findall("m:sheets/m:sheet", NS_MAIN)
	if not sheets:
		raise RuntimeError("No sheets found in workbook.xml")

	first_sheet = sheets[0]
	rid = first_sheet.attrib.get("{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id")
	if not rid:
		raise RuntimeError("First sheet has no relationship id (r:id)")

	rels = ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))
	rid_to_target = {rel.attrib["Id"]: rel.attrib["Target"] for rel in rels.findall("r:Relationship", NS_REL)}
	target = rid_to_target.get(rid)
	if not target:
		raise RuntimeError(f"Relationship target not found for {rid}")
	return f"xl/{target}"


def _read_sheet_cells(
	z: zipfile.ZipFile,
	sheet_path: str,
	shared_strings: List[str],
	columns: List[str],
) -> Dict[int, Dict[str, str]]:
	"""
	지정한 컬럼(A,B,...)만 row 단위 dict로 읽는다.
	- 반환: {rowIndex: {colLetter: cellValue}}
	"""
	sheet = ET.fromstring(z.read(sheet_path))
	rows: Dict[int, Dict[str, str]] = {}
	col_set = set(columns)

	for c in sheet.findall(".//m:sheetData/m:row/m:c", NS_MAIN):
		ref = c.attrib.get("r")
		if not ref:
			continue
		col, row = _cell_col_row(ref)
		if col not in col_set:
			continue

		v_el = c.find("m:v", NS_MAIN)
		if v_el is None:
			continue
		val = v_el.text or ""
		if c.attrib.get("t") == "s":
			try:
				val = shared_strings[int(val)]
			except Exception:
				pass
		rows.setdefault(row, {})[col] = val
	return rows


def _clean_text(v: Optional[str]) -> str:
	if v is None:
		return ""
	return str(v).replace("\n", " ").strip()


def _parse_sgng_cd(org_cell: str) -> str:
	"""
	기관명 셀(예: '인천 중구 (28110)')에서 시군구 코드(5자리)를 추출한다.
	"""
	s = _clean_text(org_cell).replace(" ", "")
	m = re.search(r"\((\d{5})\)", s)
	if m:
		return m.group(1)
	m = re.search(r"(\d{5})", s)
	return m.group(1) if m else ""

def _parse_sgng_nm(org_cell: str) -> str:
	"""
	기관명 셀(예: '인천 중구 (28110)')에서 괄호/코드를 제거한 명칭을 만든다.
	- KIKmix의 시군구명(sgng_nm_raw)은 통상 '인천광역시 중구' 형태이지만,
	  본 변경 이력 엑셀은 '인천 중구' 형태로만 제공되므로 우선 원문 기반으로 만든다.
	"""
	s = _clean_text(org_cell)
	s = re.sub(r"\(\s*\d{5}\s*\)", "", s).strip()
	s = re.sub(r"\s+", " ", s)
	return s


def _normalize_suffix5(v: str) -> str:
	"""
	법정동 꼬리값(5자리) 정규화.
	- 엑셀에서 숫자/문자/공백이 섞일 수 있어 숫자만 남긴 뒤 5자리로 left-pad 한다.
	"""
	digits = re.sub(r"[^0-9]", "", _clean_text(v))
	if not digits:
		return ""
	return digits.zfill(5)[-5:]


def _join_cd10(sgng_cd5: str, suffix5: str) -> str:
	if not sgng_cd5 or not suffix5:
		return ""
	return f"{sgng_cd5}{suffix5}"


@dataclass(frozen=True)
class MappingRow:
	old_org_nm: str
	old_sgng_cd: str
	old_admin_nm: str
	old_bjd_nm: str
	old_suffix5: str
	old_legal_dong_cd: str
	new_org_nm: str
	new_sgng_cd: str
	new_admin_nm: str
	new_bjd_nm: str
	new_suffix5: str
	new_legal_dong_cd: str


def main(argv: List[str]) -> int:
	parser = argparse.ArgumentParser()
	parser.add_argument(
		"--xlsx",
		default="행정기관(행정동) 및 관할구역(법정동) 변경 상세내역(인천광역시).xlsx",
		help="인천 변경 상세내역 xlsx 경로(기본: 프로젝트 루트 파일명).",
	)
	parser.add_argument(
		"--out-dir",
		default="scripts/out",
		help="산출물 디렉터리(기본: scripts/out).",
	)
	parser.add_argument(
		"--eff-dt",
		default="20260701",
		help="시행일(효력일) YYYYMMDD. 델타 CSV cr_dt/dlt_dt에 사용(기본: 20260701).",
	)
	args = parser.parse_args(argv)

	xlsx_path = Path(args.xlsx)
	if not xlsx_path.exists():
		print(f"ERROR: xlsx not found: {xlsx_path}", file=sys.stderr)
		return 2

	out_dir = Path(args.out_dir)
	out_dir.mkdir(parents=True, exist_ok=True)
	eff_dt = re.sub(r"[^0-9]", "", str(args.eff_dt or "")).strip()
	if len(eff_dt) != 8:
		print(f"ERROR: invalid --eff-dt (YYYYMMDD expected): {args.eff_dt}", file=sys.stderr)
		return 2

	# 이 엑셀은 (변경 전) A,B,C,D / (변경 후) E,F,G,H 구조로 보인다.
	# A/E: 기관명(시군구 코드 포함), B/F: 행정동, C/G: 법정동명, D/H: 법정동꼬리(5자리)
	cols = ["A", "B", "C", "D", "E", "F", "G", "H"]

	with zipfile.ZipFile(xlsx_path) as z:
		shared = _read_shared_strings(z)
		sheet_path = _resolve_first_sheet_target(z)
		rows = _read_sheet_cells(z, sheet_path, shared, cols)

	mappings: List[MappingRow] = []

	# 병합셀로 인한 빈칸을 직전 값으로 상속한다.
	last_old_org = ""
	last_old_admin = ""
	last_new_org = ""
	last_new_admin = ""

	for r in sorted(rows.keys()):
		# 상단 헤더(1~2행)는 스킵. 실제 데이터는 3행부터 시작하는 것으로 확인됨.
		if r < 3:
			continue

		row = rows.get(r, {})
		a = _clean_text(row.get("A"))
		b = _clean_text(row.get("B"))
		c = _clean_text(row.get("C"))
		d = _clean_text(row.get("D"))
		e = _clean_text(row.get("E"))
		f = _clean_text(row.get("F"))
		g = _clean_text(row.get("G"))
		h = _clean_text(row.get("H"))

		if a:
			last_old_org = a
		if b:
			last_old_admin = b
		if e:
			last_new_org = e
		if f:
			last_new_admin = f

		old_org = last_old_org
		old_admin = last_old_admin
		new_org = last_new_org
		new_admin = last_new_admin

		old_sgng_cd = _parse_sgng_cd(old_org)
		new_sgng_cd = _parse_sgng_cd(new_org)
		old_suffix5 = _normalize_suffix5(d)
		new_suffix5 = _normalize_suffix5(h)

		# 본문 데이터의 최소 조건: old/new suffix5 중 1개 이상 존재.
		if not old_suffix5 and not new_suffix5:
			continue

		old_cd10 = _join_cd10(old_sgng_cd, old_suffix5) if old_suffix5 else ""
		new_cd10 = _join_cd10(new_sgng_cd, new_suffix5) if new_suffix5 else ""

		mappings.append(
			MappingRow(
				old_org_nm=old_org,
				old_sgng_cd=old_sgng_cd,
				old_admin_nm=old_admin,
				old_bjd_nm=c,
				old_suffix5=old_suffix5,
				old_legal_dong_cd=old_cd10,
				new_org_nm=new_org,
				new_sgng_cd=new_sgng_cd,
				new_admin_nm=new_admin,
				new_bjd_nm=g,
				new_suffix5=new_suffix5,
				new_legal_dong_cd=new_cd10,
			)
		)

	out_csv = out_dir / "incheon_20260701_admin_bjd_mapping.csv"
	with out_csv.open("w", newline="", encoding="utf-8") as fp:
		# Windows Excel에서 UTF-8 CSV 한글이 깨지는 것을 완화하기 위해 BOM을 포함한다.
		fp.write("\ufeff")
		w = csv.writer(fp)
		w.writerow(
			[
				"old_org_nm",
				"old_sgng_cd",
				"old_admin_nm",
				"old_bjd_nm",
				"old_bjd_suffix5",
				"old_legal_dong_cd10",
				"new_org_nm",
				"new_sgng_cd",
				"new_admin_nm",
				"new_bjd_nm",
				"new_bjd_suffix5",
				"new_legal_dong_cd10",
			]
		)
		for m in mappings:
			w.writerow(
				[
					m.old_org_nm,
					m.old_sgng_cd,
					m.old_admin_nm,
					m.old_bjd_nm,
					m.old_suffix5,
					m.old_legal_dong_cd,
					m.new_org_nm,
					m.new_sgng_cd,
					m.new_admin_nm,
					m.new_bjd_nm,
					m.new_suffix5,
					m.new_legal_dong_cd,
				]
			)

	affected = sorted({m.old_sgng_cd for m in mappings if m.old_sgng_cd} | {m.new_sgng_cd for m in mappings if m.new_sgng_cd})
	out_codes = out_dir / "incheon_20260701_affected_sgng_codes.csv"
	with out_codes.open("w", newline="", encoding="utf-8") as fp:
		fp.write("\ufeff")
		w = csv.writer(fp)
		w.writerow(["sgng_cd5"])
		for cd in affected:
			w.writerow([cd])

	print(f"OK: rows={len(mappings)} -> {out_csv}")
	print(f"OK: affected_sgng_cd5={len(affected)} -> {out_codes}")

	# =========================================================
	# (선택) SBB 마이그레이션 입력 형태 8컬럼 "델타 CSV" 생성
	# =========================================================
	# 주의
	# - 이 파일은 KIKmix 전체 스냅샷이 아니라, 변경 이력에 등장하는 법정동코드만 대상으로 한다.
	# - admin_cd_raw(행정동코드)는 본 엑셀에 없으므로 빈 값으로 둔다(프로시저는 admin_cd_raw를 사용하지 않음).
	# - old 코드는 "말소일자(dlt_dt)=시행일", new 코드는 "생성일자(cr_dt)=시행일"로 기록한다.
	# - old 코드의 생성일자(cr_dt)는 제공 데이터가 없으므로 빈 값(NULL)로 둔다.
	delta_path = out_dir / "incheon_20260701_delta_8cols.csv"
	seen = set()
	delta_rows = []
	for m in mappings:
		# old (dlt)
		if m.old_legal_dong_cd and m.old_legal_dong_cd not in seen:
			delta_rows.append(
				[
					"",  # 행정동코드(미제공)
					"인천광역시",  # 시도명
					_parse_sgng_nm(m.old_org_nm),  # 시군구명
					m.old_admin_nm,  # 읍면동명(행정동)
					m.old_legal_dong_cd,  # 법정동코드(10)
					m.old_bjd_nm,  # 동리명
					"",  # 생성일자(미제공)
					eff_dt,  # 말소일자
				]
			)
			seen.add(m.old_legal_dong_cd)

	# new는 old와 코드가 다를 수 있으므로 별도 seen 관리
	seen_new = set()
	for m in mappings:
		if m.new_legal_dong_cd and m.new_legal_dong_cd not in seen_new:
			delta_rows.append(
				[
					"",
					"인천광역시",
					_parse_sgng_nm(m.new_org_nm),
					m.new_admin_nm,
					m.new_legal_dong_cd,
					m.new_bjd_nm,
					eff_dt,
					"",
				]
			)
			seen_new.add(m.new_legal_dong_cd)

	with delta_path.open("w", newline="", encoding="utf-8") as fp:
		fp.write("\ufeff")
		w = csv.writer(fp)
		w.writerow(["행정동코드", "시도명", "시군구명", "읍면동명", "법정동코드", "동리명", "생성일자", "말소일자"])
		w.writerows(delta_rows)

	print(f"OK: delta_8cols rows={len(delta_rows)} -> {delta_path}")
	return 0


if __name__ == "__main__":
	sys.exit(main(sys.argv[1:]))
