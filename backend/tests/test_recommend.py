"""后端推荐逻辑的边界测试。

与 Kotlin 侧 RecommendEngineTest 的用例一一对应：两边是两份实现，
只有用同一组边界值才能保证它们不漂。跑法：

    cd backend && python -m unittest discover -s tests -v
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.main import (  # noqa: E402
    DeviceProfile,
    load_rules,
    nominal_gb,
    pick_threads,
    recommend,
)

RULES = load_rules()


class NominalGbTest(unittest.TestCase):
    def test_total_mem_compensation(self):
        # 实测值 → 应归并到的标称档位
        cases = {
            3584: 4,     # 标称 4GB
            5734: 6,     # 标称 6GB，实测 5.6GB
            7680: 8,     # 标称 8GB，实测 7.5GB
            11674: 12,   # 标称 12GB，实测 11.4GB
            15872: 16,   # 标称 16GB
        }
        for total, expected in cases.items():
            with self.subTest(total=total):
                self.assertEqual(expected, nominal_gb(total, RULES))


class ThreadsTest(unittest.TestCase):
    def test_cap_at_six(self):
        self.assertEqual(6, pick_threads(8, RULES))
        self.assertEqual(4, pick_threads(4, RULES))
        self.assertEqual(RULES["threads"]["defaultCores"], pick_threads(0, RULES))


class RecommendTest(unittest.TestCase):
    def _profile(self, total_mem, cores=8, gles="3.2", renderer="Mali-G57", storage=20000):
        return DeviceProfile(
            socModel="test",
            cpuCores=cores,
            totalMemMb=total_mem,
            freeStorageMb=storage,
            glesVersion=gles,
            gpuRenderer=renderer,
        )

    def test_2gb_device_gets_extreme(self):
        # DNAKE v911 实测 1950MB：不属于"拒绝"，而是给极限档
        result = recommend(self._profile(1950))
        self.assertTrue(result.supported)
        self.assertTrue(result.restricted)
        self.assertEqual("Qwen2.5-0.5B-Instruct-MNN", result.modelId)
        self.assertEqual(1024, result.contextLength)
        self.assertFalse(result.useGpu, "极限档必须关 GPU")
        self.assertTrue(result.useMmap, "极限档必须开 mmap，否则权重整块进 RSS")

    def test_below_1_5gb_rejected(self):
        result = recommend(self._profile(1200))
        self.assertFalse(result.supported)
        self.assertIn("不足", result.reason)

    def test_6gb_gets_1_5b(self):
        result = recommend(self._profile(5734))
        self.assertTrue(result.supported)
        self.assertEqual("Qwen2.5-1.5B-Instruct-MNN", result.modelId)
        self.assertEqual(3072, result.contextLength)

    def test_8gb_gets_4k_context(self):
        result = recommend(self._profile(7680))
        self.assertEqual(4096, result.contextLength)

    def test_12gb_gets_3b(self):
        result = recommend(self._profile(11674))
        self.assertEqual("Qwen2.5-3B-Instruct-MNN", result.modelId)

    def test_gpu_requires_renderer(self):
        self.assertFalse(recommend(self._profile(7680, renderer=None)).useGpu)
        self.assertFalse(recommend(self._profile(7680, gles="2.0")).useGpu)
        self.assertTrue(recommend(self._profile(7680, renderer="Adreno (TM) 740")).useGpu)

    def test_mmap_only_when_mem_tight_and_storage_plenty(self):
        self.assertTrue(recommend(self._profile(7680, storage=20000)).useMmap)
        self.assertFalse(recommend(self._profile(7680, storage=1000)).useMmap)
        self.assertFalse(recommend(self._profile(15872, storage=50000)).useMmap)


if __name__ == "__main__":
    unittest.main()
