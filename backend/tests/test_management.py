from django.core.management import get_commands


def test_llm_check_command_is_registered():
    assert get_commands().get("llm_check") == "careerpilot"
