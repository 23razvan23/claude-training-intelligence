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
