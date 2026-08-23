from app.services.qwen_client import extract_final_answer


def test_extract_final_answer_separates_reasoning_content():
    message = {"reasoning_content": "private reasoning", "content": "final answer"}
    assert extract_final_answer(message) == "final answer"


def test_extract_final_answer_removes_think_block_from_content():
    message = {"content": "<think>private reasoning</think>\n\nfinal answer"}
    assert extract_final_answer(message) == "final answer"


def test_extract_final_answer_removes_leaked_prefix_ending_with_think_tag():
    message = {"content": "我们需要回答用户：分析资料。Let's final. </think>\n正式回答"}
    assert extract_final_answer(message) == "正式回答"


def test_extract_final_answer_preserves_normal_text():
    message = {"content": "This is a normal final answer."}
    assert extract_final_answer(message) == "This is a normal final answer."


def test_extract_final_answer_rejects_unclosed_think_block():
    message = {"content": "<think>private reasoning without a closing tag"}
    assert extract_final_answer(message) == ""
