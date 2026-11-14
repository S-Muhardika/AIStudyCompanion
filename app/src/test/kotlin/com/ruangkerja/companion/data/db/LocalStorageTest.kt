package com.ruangkerja.companion.data.db

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * [BARU, 29 Okt — audit Tahap 3] Unit test PERTAMA di proyek ini — nutup sebagian gap "nol test
 * coverage" dari laporan audit, fokus ke LocalStorage dulu karena itu lapisan paling berisiko
 * (satu-satunya jalur persistensi data user) DAN baru aja diubah buat fix B1 (tulis atomik +
 * quarantine file korup, lihat LocalStorage.kt). Belum nutup semua fungsi LocalStorage — ini
 * putaran pertama, fokus ke perilaku yang BARU diubah + roundtrip dasar, bukan audit lengkap.
 *
 * Context di-mock (BUKAN Robolectric) karena LocalStorage cuma nyentuh context.filesDir doang
 * (lihat constructor-nya) — gak butuh simulasi Android framework penuh.
 *
 * CATATAN JUJUR: file ini ditulis & ditelusuri manual tanpa compiler (sandbox nggak bisa
 * ./gradlew), sama kayak semua perbaikan lain di putaran audit ini — WAJIB dijalanin
 * (./gradlew test) di AndroidIDE buat mastiin beneran lulus, bukan diasumsikan lulus dari sini.
 */
class LocalStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = tempFolder.newFolder("filesDir")
        context = mock()
        whenever(context.filesDir).thenReturn(dir)
    }

    @Test
    fun `insert workspace menulis file JSON valid tanpa menyisakan file tmp`() = runTest {
        val storage = LocalStorage(context)
        storage.insertWorkspace(Workspace(name = "Matematika"))

        val file = File(dir, "workspaces.json")
        assertTrue("workspaces.json harus ada setelah insert", file.exists())
        assertFalse(
            "writeAtomic() harus selalu bersih — tidak boleh ada file .tmp tersisa setelah save sukses",
            File(dir, "workspaces.json.tmp").exists()
        )
        assertTrue("isi file harus memuat data yang baru disimpan", file.readText().contains("Matematika"))
    }

    @Test
    fun `data yang disimpan tetap kebaca setelah LocalStorage dibuat ulang (simulasi restart app)`() = runTest {
        val storage1 = LocalStorage(context)
        storage1.insertWorkspace(Workspace(id = "ws1", name = "Matematika", description = "Semester 1"))

        // LocalStorage baru dari context yang SAMA (dir temp yang sama) — simulasikan app
        // ditutup lalu dibuka lagi, baca ulang dari disk dari nol.
        val storage2 = LocalStorage(context)
        storage2.awaitFullyLoaded()
        val loaded = storage2.allWorkspacesOnce()

        assertEquals(1, loaded.size)
        assertEquals("ws1", loaded[0].id)
        assertEquals("Matematika", loaded[0].name)
    }

    @Test
    fun `file JSON korup di-quarantine, bukan ditimpa diam-diam jadi kosong permanen`() = runTest {
        // Simulasikan file korup: proses mati di tengah nulis sebelum fix writeAtomic() ada,
        // atau korup karena sebab lain apa pun — isinya bukan JSON valid.
        File(dir, "workspaces.json").writeText("{ ini bukan json valid sama sekali !!!")

        val storage = LocalStorage(context)
        storage.awaitFullyLoaded()

        assertTrue(
            "load() yang gagal parse tetap balik ke list kosong di memori (perilaku lama tidak berubah)",
            storage.allWorkspacesOnce().isEmpty()
        )
        assertFalse(
            "file ASLI yang korup harus sudah dipindah oleh quarantineCorruptFile(), bukan nyangkut di nama lama",
            File(dir, "workspaces.json").exists()
        )
        val quarantined = dir.listFiles { f -> f.name.startsWith("workspaces.json.corrupt-") }
        assertNotNull("harus ada file quarantine yang menyimpan jejak file korup tadi", quarantined)
        assertEquals(1, quarantined!!.size)
        assertTrue(
            "isi file korup yang lama harus tetap utuh di file quarantine, tidak hilang",
            quarantined!![0].readText().contains("ini bukan json valid")
        )

        // Insert baru setelah quarantine — harus nulis ke workspaces.json yang "bersih" (slot
        // yang sah, bukan nimpa file korup yang masih mungkin dipulihkan manual).
        storage.insertWorkspace(Workspace(name = "Fisika"))
        val fresh = File(dir, "workspaces.json").readText()
        assertTrue(fresh.contains("Fisika"))
        assertFalse("data lama yang korup tidak boleh ikut nyampur di file baru", fresh.contains("ini bukan json valid"))
    }
}
