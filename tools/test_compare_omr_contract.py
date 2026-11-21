import copy
import unittest

from compare_omr_contract import compare


def response():
    return {"sheets": [{"pageNumber": 1, "success": True, "reviewRequired": True,
        "questions": [{"question": 15, "status": "MARKED", "selectedOptionNumber": 2,
            "selectedOptions": ["2"], "rawFillRatios": [.90, .95, 0, 0], "confidence": .79}]}]}


class ComparisonTests(unittest.TestCase):
    def test_missing_site_is_never_equality(self):
        rows, summary = compare(response())
        self.assertFalse(summary["outputsEqual"])
        self.assertEqual(summary["siteErrors"], ["site_not_run"])
        self.assertEqual(summary["comparedQuestions"], 0)
        self.assertEqual(rows[0]["comparison"], "NOT_RUN")
        self.assertIn("NOT_RUN", rows[0]["site_status"])

    def test_matching_invalid_results_are_not_success(self):
        for field, value in (("status", "MULTI"), ("confidence", .8),
                             ("selectedOptionNumber", 1), ("selectedOptions", ["1", "2"])):
            with self.subTest(field=field):
                data = response()
                data["sheets"][0]["questions"][0][field] = value
                self.assertFalse(compare(data, data)[1]["outputsEqual"])

    def test_review_is_required_for_a_close_pair(self):
        data = response()
        data["sheets"][0]["reviewRequired"] = False
        self.assertFalse(compare(data, data)[1]["outputsEqual"])

    def test_missing_question_or_failed_scan_is_not_equal(self):
        left = response()
        right = copy.deepcopy(left)
        right["sheets"][0]["questions"] = []
        self.assertFalse(compare(left, right)[1]["outputsEqual"])
        right = copy.deepcopy(left)
        right["sheets"][0]["success"] = False
        self.assertFalse(compare(left, right)[1]["outputsEqual"])

    def test_all_reported_values_must_match(self):
        left = response()
        self.assertTrue(compare(left, copy.deepcopy(left))[1]["outputsEqual"])
        right = copy.deepcopy(left)
        right["sheets"][0]["questions"][0]["rawFillRatios"][0] += .000001
        self.assertFalse(compare(left, right)[1]["outputsEqual"])


if __name__ == "__main__":
    unittest.main()
