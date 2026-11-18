import io
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import inspect_exports  # noqa: E402

CYCLES = (
    "Cycle start time,Cycle end time,Cycle timezone,Recovery score %,Day Strain,Notes\n"
    "2024-03-01 07:00:00,2024-03-02 07:00:00,UTC+02:00,55,12.1,private note\n"
    "2024-03-02 07:00:00,2024-03-03 07:00:00,UTC+02:00,70,9.5,\n"
    "2024-03-05 07:00:00,2024-03-06 07:00:00,UTC+02:00,30,15.0,another private note\n"
)


def test_csv_summary_reports_ranges_and_gaps_without_text_values():
    out = "\n".join(inspect_exports.inspect_csv("physiological_cycles.csv", CYCLES))
    assert "3 rows, 6 columns" in out
    assert "min=30.00 max=70.00" in out
    assert "3/5 days present, 2 missing" in out
    assert "missing 2024-03-03 .. 2024-03-04" in out
    assert "private note" not in out


def test_garmin_zip_summary(tmp_path):
    inner = io.BytesIO()
    with zipfile.ZipFile(inner, "w") as z:
        z.writestr("a.fit", b"x")
        z.writestr("b.fit", b"y")
    path = tmp_path / "garmin.zip"
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(
            "DI_CONNECT/DI-Connect-Wellness/x_sleepData.json",
            json.dumps([{"calendarDate": "2024-03-01", "deepSleepSeconds": 6000, "sleepScores": {"overallScore": 80}}]),
        )
        z.writestr(
            "DI_CONNECT/DI-Connect-Fitness/u_summarizedActivities.json",
            json.dumps([{"summarizedActivitiesExport": [{"activityId": 1, "startTimeGmt": 1709312400000.0}]}]),
        )
        z.writestr("DI_CONNECT/DI-Connect-Uploaded-Files/UploadedFiles_0-_Part1.zip", inner.getvalue())
    out = "\n".join(inspect_exports.inspect_zip(str(path)))
    assert "sleepData: 1 files, 1 records" in out
    assert "sleepScores/overallScore" in out
    assert "summarizedActivities: 1 files, 1 records" in out
    assert "FIT files: 2 (including 1 nested ZIPs)" in out
    assert "WARNING: no UDSFile (daily summary) files matched" in out


def test_main_without_args_prints_usage(capsys):
    assert inspect_exports.main([]) == 2
    assert "Usage" in capsys.readouterr().out


def test_lists_of_objects_are_described_with_enum_values_but_no_free_text():
    blob = json.dumps(
        [
            {
                "calendarDate": "2024-03-01",
                "allDayStress": {
                    "aggregatorList": [
                        {"type": "TOTAL", "averageStressLevel": 30},
                        {"type": "AWAKE", "averageStressLevel": 40},
                    ]
                },
                "metrics": [{"type": "HRV", "value": 55.0, "status": "IN_RANGE"}],
                "feedback": "You slept like a baby after the party at Ana's",
            }
        ]
    ).encode()
    out = "\n".join(inspect_exports.inspect_json_group("x", [("x.json", blob)]))
    assert "allDayStress/aggregatorList[]/type" in out
    assert "values   AWAKE, TOTAL" in out
    assert "metrics[]/value" in out
    assert "values   HRV" in out
    assert "values   IN_RANGE" in out
    assert "party" not in out and "Ana" not in out


def test_file_listing_masks_digits(tmp_path):
    path = tmp_path / "g.zip"
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("DI_CONNECT/DI-Connect-Metrics/TrainingReadinessDTO_20240101_20240410_127236701.json", "[]")
        z.writestr("DI_CONNECT/DI-Connect-Metrics/TrainingReadinessDTO_20240411_20240720_127236701.json", "[]")
    out = "\n".join(inspect_exports.inspect_zip(str(path), show_files=True))
    assert "2  DI_CONNECT/DI-Connect-Metrics/TrainingReadinessDTO_#_#_#.json" in out
    assert "127236701" not in out


def test_rpe_feel_summary_prints_only_numbers_and_counts():
    recs = [
        {"activityId": 1, "name": "Secret run", "startTimeGmt": 1709312400000.0, "workoutRpe": 50, "workoutFeel": 75},
        {"activityId": 2, "startTimeGmt": 1709398800000.0, "workoutRpe": 50, "workoutFeel": 100},
        {"activityId": 3, "startTimeGmt": 1709485200000.0, "workoutRpe": 70, "workoutFeel": None},
        {"activityId": 4, "startTimeGmt": 1709571600000.0, "workoutRpe": "hard"},
        {"activityId": 5, "startTimeGmt": 1709658000000.0},
    ]
    blob = json.dumps([{"summarizedActivitiesExport": recs}]).encode()
    out = "\n".join(inspect_exports.inspect_json_group("x", [("x.json", blob)]))
    assert "workoutRpe: 3 numeric, 1 empty or missing, 1 not a number" in out
    assert "min=50 max=70; multiples of 10: 3 of 3" in out
    assert "50x2, 70x1" in out
    assert "workoutFeel: 2 numeric, 3 empty or missing, 0 not a number" in out
    assert "records with both: 2" in out
    assert "(50, 75)x1, (50, 100)x1" in out
    assert "Secret run" not in out and "hard" not in out.split("Per-activity effort")[1]


def test_no_rpe_section_when_fields_absent():
    blob = json.dumps([{"activityId": 1}]).encode()
    out = "\n".join(inspect_exports.inspect_json_group("x", [("x.json", blob)]))
    assert "Per-activity effort" not in out
