"""规则表与模型清单的一致性测试。

存在理由：这两份文件是分别维护的——
`rules/recommend-rules.json` 说"8GB 用 1.5B"，`assets/model-catalog.json` 说"清单里有哪几个模型"。
只要有一边改了名字或漏了条目，App 就会安静地下载错模型，而且不报错。
这组测试把这个坑钉死。
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.main import load_catalog, load_rules  # noqa: E402


class CatalogConsistencyTest(unittest.TestCase):

    def setUp(self):
        self.rules = load_rules()
        self.catalog = load_catalog()
        self.by_id = {m["id"]: m for m in self.catalog["models"]}

    def test_every_recommended_model_exists_in_catalog(self):
        for tier in self.rules["memTiers"]:
            with self.subTest(tier=tier["nominalGb"]):
                self.assertIn(
                    tier["modelId"],
                    self.by_id,
                    f"{tier['nominalGb']}GB 档推荐了清单里不存在的模型：{tier['modelId']}",
                )

    def test_every_file_has_sha256(self):
        for model in self.catalog["models"]:
            for f in model["files"]:
                with self.subTest(model=model["id"], file=f["name"]):
                    self.assertRegex(
                        f["sha256"], r"^[0-9a-f]{64}$",
                        "校验和必须是完整的 64 位 sha256，否则断点续传的完整性保证形同虚设",
                    )

    def test_total_bytes_matches_file_sum(self):
        for model in self.catalog["models"]:
            with self.subTest(model=model["id"]):
                self.assertEqual(
                    model["totalBytes"],
                    sum(f["size"] for f in model["files"]),
                    "totalBytes 必须等于各文件之和，否则进度条会超过 100%",
                )

    def test_required_files_present(self):
        """config.json 是 createLLM 的入口，缺了它模型根本加载不起来"""
        for model in self.catalog["models"]:
            names = {f["name"] for f in model["files"]}
            with self.subTest(model=model["id"]):
                self.assertIn("config.json", names)
                self.assertIn("llm.mnn.weight", names)

    def test_recommended_context_not_exceeding_model_max(self):
        for tier in self.rules["memTiers"]:
            model = self.by_id.get(tier["modelId"])
            if model is None:
                continue
            with self.subTest(tier=tier["nominalGb"]):
                self.assertLessEqual(
                    tier["contextLength"], model["contextLength"],
                    "规则表给的上下文长度超过了模型自身支持的上限",
                )


if __name__ == "__main__":
    unittest.main()
