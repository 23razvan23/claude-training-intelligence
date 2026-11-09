from calibration.__main__ import main


def test_placeholder_exits_nonzero():
    assert main() == 2
