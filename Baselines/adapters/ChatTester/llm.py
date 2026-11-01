"""LLM-facing part of ChatTester adapted to the shared DeepSeek client.

This module preserves ChatTester's intention -> initial test -> repair stages,
while removing its direct dependency on the legacy OpenAI Python package.
"""

from __future__ import print_function

import re
from pathlib import Path

from deepseek_gateway import generate


INTENTION_SYSTEM_PROMPT = (
    "I want you to play the role of a professional who infers method intention."
)
INITIAL_TEST_SYSTEM_PROMPT = (
    "I want you to play the role of a professional who writes Java test method."
)
REPAIR_TEST_SYSTEM_PROMPT = (
    "I want you to play the role of a professional who repairs buggy lines of "
    "the test method. Unnecessary import statement can be removed."
)


class ChatTesterLlmAdapter(object):
    """Drop-in replacement for the LLM methods of ChatTester's ``Unit`` class."""

    def __init__(self, run_dir, baseline="ChatTester-DS-Adapted"):
        self.run_dir = Path(run_dir).resolve()
        self.baseline = baseline
        self.run_dir.mkdir(parents=True, exist_ok=True)

    def intention_unit(self, focal_method_context, focal_method_name, round_number=0):
        request = (
            focal_method_context
            + "\n\nPlease describe the overall intention of the {0} method "
            "in as much detail as possible in one sentence.".format(
                focal_method_name
            )
        )
        response = generate(
            baseline=self.baseline,
            stage="chattester_intention",
            system_prompt=INTENTION_SYSTEM_PROMPT,
            user_prompt=request,
            run_dir=self.run_dir,
            max_tokens=0,
            round_number=round_number,
        )
        return " ".join(response["content"].split())

    def method_pred_unit(
        self,
        test_prompt,
        repair_TAG=False,
        repair_stage="compile",
        round_number=0,
    ):
        if repair_TAG:
            if repair_stage not in ("compile", "runtime"):
                raise ValueError("repair_stage must be 'compile' or 'runtime'")
            stage = "chattester_{0}_repair".format(repair_stage)
            system_prompt = REPAIR_TEST_SYSTEM_PROMPT
        else:
            stage = "chattester_initial"
            system_prompt = INITIAL_TEST_SYSTEM_PROMPT

        response = generate(
            baseline=self.baseline,
            stage=stage,
            system_prompt=system_prompt,
            user_prompt=test_prompt,
            run_dir=self.run_dir,
            max_tokens=0,
            round_number=round_number,
        )
        return self.return_code(response["content"])

    @staticmethod
    def return_code(generated_content):
        """Preserve ChatTester's extraction of a test method and imports."""
        normalized = "\n".join(
            line
            for line in generated_content.split("\n")
            if "Below is " not in line
        )
        normalized = (
            normalized.replace("(Fixed)", "")
            .replace("java\r\n", "")
            .replace("java\n", "")
            .replace("Java\n", "")
            .replace("# Test Method", "")
        )

        fenced = re.findall(r"```(?:java)?\s*(.*?)```", normalized, re.DOTALL)
        code_candidates = [
            candidate
            for candidate in fenced
            if len(candidate) > 5 and " void " in candidate
        ]
        java_code = code_candidates[-1] if code_candidates else normalized
        lines = java_code.split("\n")

        imports = [line.strip() for line in lines if line.strip().startswith("import ")]

        code_block = []
        brace_balance = 0
        started = False
        seen_opening_brace = False
        for line in lines:
            if not started and ("@Test" in line or " void " in line):
                started = True
                if "@Test" not in line:
                    code_block.append("@Test")
            if not started:
                continue

            code_block.append(line)
            brace_balance += line.count("{") - line.count("}")
            if "{" in line:
                seen_opening_brace = True
            if seen_opening_brace and brace_balance == 0:
                break

        return "\n".join(code_block).strip(), "\n".join(imports)
