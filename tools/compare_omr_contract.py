"""Compare per-question mobile/site JSON without treating missing runs as success.

Both inputs use the public batch response (sheets[].questions[]). This tool
does not run either engine or certify that the inputs came from the same photo.
Keep the requests, image hashes, and build provenance alongside its output.
"""
import argparse
import csv
import json
from pathlib import Path


FIELDS = ("status", "selectedOptionNumber", "selectedOptions", "rawFillRatios", "confidence")


def observations(response):
    rows = {}
    errors = []
    for sheet in response.get("sheets", []):
        page = sheet.get("pageNumber", sheet.get("page"))
        if page is None:
            errors.append("sheet_missing_page_number")
            continue
        if not sheet.get("success"):
            errors.append(f"page_{page}:scan_failed")
        questions = sheet.get("questions", [])
        if not questions:
            errors.append(f"page_{page}:no_questions")
        for question in questions:
            key = (page, question.get("question", question.get("questionNumber")))
            if key[1] is None or key in rows:
                errors.append(f"{key}:missing_or_duplicate_question")
            rows[key] = question
            absent = [name for name in FIELDS if name not in question]
            if absent:
                errors.append(f"{key}:missing_fields:{','.join(absent)}")
                continue
            ratios = question["rawFillRatios"]
            selected = question["selectedOptionNumber"]
            choices = question["selectedOptions"]
            status = question["status"]
            if len(ratios) != 4 or any(not 0 <= value <= 1 for value in ratios):
                errors.append(f"{key}:invalid_fill_ratios")
                continue
            if not 0 <= question["confidence"] <= 1:
                errors.append(f"{key}:invalid_confidence")
            if status == "BLANK":
                if selected is not None or choices:
                    errors.append(f"{key}:blank_has_selection")
            elif status == "MARKED":
                if selected not in (1, 2, 3, 4) or len(choices) != 1 or str(choices[0]) != str(selected):
                    errors.append(f"{key}:invalid_single_selection")
                elif ratios[selected - 1] != max(ratios):
                    errors.append(f"{key}:winner_is_not_maximum_fill")
                best = sorted(ratios, reverse=True)
                if best[0] - best[1] < .10 and question["confidence"] > .79:
                    errors.append(f"{key}:close_fill_confidence_above_cap")
                if best[0] - best[1] < .10 and not sheet.get("reviewRequired"):
                    errors.append(f"{key}:close_fill_review_missing")
            else:
                errors.append(f"{key}:forbidden_answer_status:{status}")
    if not rows:
        errors.append("no_observations")
    return rows, errors


def compare(mobile, site=None):
    mobile_rows, mobile_errors = observations(mobile)
    site_rows, site_errors = observations(site) if site is not None else ({}, ["site_not_run"])
    rows = []
    differences = []
    for page, number in sorted(mobile_rows.keys() | site_rows.keys()):
        key = (page, number)
        left, right = mobile_rows.get(key), site_rows.get(key)
        mismatch = [field for field in FIELDS if left is None or right is None or left.get(field) != right.get(field)]
        if mismatch:
            differences.append({"page": page, "question": number, "fields": mismatch})
        state = "NOT_RUN" if left is None or right is None else "DIFFERENT" if mismatch else "EQUAL"
        row = {"page": page, "question": number, "comparison": state}
        for prefix, question in (("mobile", left), ("site", right)):
            for field in FIELDS:
                value = question.get(field) if question else "NOT_RUN"
                row[f"{prefix}_{field}"] = json.dumps(value, ensure_ascii=False)
        rows.append(row)
    summary = {
        "outputsEqual": not differences and not mobile_errors and not site_errors,
        "mobileQuestions": len(mobile_rows),
        "siteQuestions": len(site_rows),
        "comparedQuestions": len(mobile_rows.keys() & site_rows.keys()),
        "uncomparedQuestions": len(mobile_rows.keys() ^ site_rows.keys()),
        "mobileErrors": mobile_errors,
        "siteErrors": site_errors,
        "differences": differences,
        "scope": "JSON contract only; real-photo coverage and input/build provenance require separate verification",
    }
    return rows, summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mobile", type=Path, required=True)
    parser.add_argument("--site", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    read = lambda path: json.loads(path.read_text(encoding="utf-8-sig"))
    rows, summary = compare(read(args.mobile), read(args.site) if args.site else None)
    args.output.mkdir(parents=True, exist_ok=True)
    columns = ["page", "question", "comparison"] + [f"{prefix}_{field}" for prefix in ("mobile", "site") for field in FIELDS]
    with (args.output / "question-comparison.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)
    (args.output / "comparison-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in summary.items() if key != "differences"}))
    return 0 if summary["outputsEqual"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
