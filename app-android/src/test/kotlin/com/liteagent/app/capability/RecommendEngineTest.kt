package com.liteagent.app.capability

import com.liteagent.shared.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推荐规则的边界测试。
 *
 * 这些用例存在的理由很具体：`totalMem` 比标称容量小 0.4–0.8GB，
 * 如果直接拿实测值比阈值，6GB 机会被降级、`>=12GB` 那档永远命中不了。
 * 下面每个用例都对应一个真实机型的实测值。
 */
class RecommendEngineTest {

    private val engine = RecommendEngine(
        RecommendRules(
            unsupported = UnsupportedRule(
                belowTotalMemMb = 1500,
                message = "内存不足 X，无法运行"
            ),
            extreme = ExtremeRule(
                aboveTotalMemMb = 1500,
                belowTotalMemMb = 3500,
                modelId = "Qwen2.5-0.5B-Instruct-MNN",
                contextLength = 1024,
                threads = 3,
                useGpu = false,
                reason = "极限模式：0.5B + 1K 上下文"
            ),
            memTiers = listOf(
                MemTier(4, "Qwen2.5-0.5B-Instruct-MNN", "W4", 2048, "稳定优先"),
                MemTier(6, "Qwen2.5-1.5B-Instruct-MNN", "W4", 3072, "1.5B 甜点"),
                MemTier(8, "Qwen2.5-1.5B-Instruct-MNN", "W4", 4096, "1.5B 4K"),
                MemTier(12, "Qwen2.5-3B-Instruct-MNN", "W4", 4096, "3B 更强"),
                MemTier(16, "Qwen2.5-3B-Instruct-MNN", "W4", 4096, "3B 甜点")
            ),
            threads = ThreadRule(defaultCores = 4, maxCores = 6),
            gpu = GpuRule(minGlesMajor = 3, supportedRendererPrefixes = listOf("Adreno", "Mali", "Immortalis")),
            mmap = MmapRule(enabledWhenFreeStorageMbAbove = 4096, enabledWhenTotalMemMbBelow = 8192)
        )
    )

    private fun profile(
        totalMemMb: Long,
        cores: Int = 8,
        gles: String = "3.2",
        renderer: String? = "Mali-G57",
        storageMb: Long = 20_000
    ) = DeviceProfile(
        socModel = "test",
        cpuCores = cores,
        totalMemMb = totalMemMb,
        freeStorageMb = storageMb,
        glesVersion = gles,
        gpuRenderer = renderer,
        isArm64 = true
    )

    @Test
    fun `2GB 工控机走极限档而不是拒绝`() {
        // 一台国产安卓工控设备 实测 totalMem 1950MB。
        // 判它"能跑"的依据：0.5B 的 embeddings 走 MNN 的 DiskEmbedding 不占内存，
        // 常驻只有权重 + KV + workspace，约 300-450MB，塞得进 692MB 可用内存。
        val profile = profile(1950)
        val result = engine.recommend(profile)

        assertTrue("应给极限档", result is Recommendation.Restricted)
        val model = (result as Recommendation.Restricted).model
        assertEquals("Qwen2.5-0.5B-Instruct-MNN", model.modelId)
        assertEquals(1024, model.contextLength)
        assertEquals(3, model.threads)
        assertFalse("极限档必须关 GPU：统一内存下 GPU 会抢 RAM", model.useGpu)
        assertTrue("极限档必须开 mmap", model.useMmap)
    }

    @Test
    fun `低于 1_5GB 仍然拒绝`() {
        val result = engine.recommend(profile(1200))
        assertTrue(result is Recommendation.Unsupported)
    }

    @Test
    fun `正常档位不受极限档影响`() {
        val result = engine.recommend(profile(7680))
        assertTrue("8GB 机仍走常规推荐", result is Recommendation.Supported)
    }

    @Test
    fun `6GB 机不能掉到 0_5B`() {
        // 标称 6GB 实测约 5.6GB = 5734MB
        val result = engine.recommend(profile(5734)) as Recommendation.Supported
        assertEquals("Qwen2.5-1.5B-Instruct-MNN", result.model.modelId)
        assertEquals(3072, result.model.contextLength)
    }

    @Test
    fun `8GB 机落在 8GB 档`() {
        // 实测约 7.5GB = 7680MB，修正后应归到 8 档而不是 6 档
        assertEquals(8, engine.nominalGb(7680))
        val result = engine.recommend(profile(7680)) as Recommendation.Supported
        assertEquals(4096, result.model.contextLength)
    }

    @Test
    fun `12GB 机命中 3B`() {
        // 实测约 11.4GB = 11674MB
        assertEquals(12, engine.nominalGb(11674))
        val result = engine.recommend(profile(11674)) as Recommendation.Supported
        assertEquals("Qwen2.5-3B-Instruct-MNN", result.model.modelId)
    }

    @Test
    fun `16GB 档可达`() {
        // 老规则里 ">=12GB" 那档在实际设备上永远命中不了，这里用 16GB 验证它可达
        assertEquals(16, engine.nominalGb(15872))
    }

    @Test
    fun `4GB 机用最小模型`() {
        val result = engine.recommend(profile(3584)) as Recommendation.Supported
        assertEquals("Qwen2.5-0.5B-Instruct-MNN", result.model.modelId)
    }

    @Test
    fun `线程数封顶 6`() {
        val eight = engine.recommend(profile(7680, cores = 8)) as Recommendation.Supported
        assertEquals(6, eight.model.threads)

        val four = engine.recommend(profile(7680, cores = 4)) as Recommendation.Supported
        assertEquals(4, four.model.threads)
    }

    @Test
    fun `拿不到 GPU 型号就不开 GPU`() {
        val noRenderer = engine.recommend(profile(7680, renderer = null)) as Recommendation.Supported
        assertFalse(noRenderer.model.useGpu)

        val oldGles = engine.recommend(profile(7680, gles = "2.0")) as Recommendation.Supported
        assertFalse(oldGles.model.useGpu)

        val mali = engine.recommend(profile(7680, renderer = "Mali-G715")) as Recommendation.Supported
        assertTrue(mali.model.useGpu)
    }

    @Test
    fun `内存紧张且存储充足才开 mmap`() {
        val tight = engine.recommend(profile(7680, storageMb = 20_000)) as Recommendation.Supported
        assertTrue(tight.model.useMmap)

        val lowStorage = engine.recommend(profile(7680, storageMb = 1000)) as Recommendation.Supported
        assertFalse(lowStorage.model.useMmap)

        val plenty = engine.recommend(profile(15872, storageMb = 50_000)) as Recommendation.Supported
        assertFalse("内存充裕时不必付 mmap 的首次加载代价", plenty.model.useMmap)
    }

    @Test
    fun `推荐理由包含内存与芯片信息`() {
        val result = engine.recommend(profile(7680)) as Recommendation.Supported
        assertTrue(result.model.reason.contains("7.5GB"))
        assertTrue(result.model.reason.contains("test"))
    }
}
