package com.example.pedigreeapp

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    // 声明 C 语言 JNI 函数
    external fun runCalculationNative(inputPath: String, outputPath: String): String

    companion object {
        init {
            System.loadLibrary("pedigree_native") // 加载 so 库
        }
    }

    private lateinit var tvStatus: TextView
    private var selectedInputUri: Uri? = null

    private val pickInputFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            selectedInputUri = it
            tvStatus.text = "已选择输入文件，点击开始计算"
        }
    }

    private val createOutputFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-sqlite3")) { uri: Uri? ->
        uri?.let { saveOutputToUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnSelect = findViewById<Button>(R.id.btnSelect)
        val btnStart = findViewById<Button>(R.id.btnStart)
        tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnSelect.setOnClickListener { pickInputFile.launch(arrayOf("*/*")) }
        btnStart.setOnClickListener {
            if (selectedInputUri == null) {
                Toast.makeText(this, "请先选择输入数据库", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startCalculation()
        }
    }

    private fun startCalculation() {
        tvStatus.text = "正在准备数据 (复制到内部存储)..."
        
        // 【关键】必须在 IO 线程运行，防止 UI 卡死 (ANR)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 绕过 Android 沙盒：将用户选择的 DB 复制到 APP 内部缓存
                val internalInputFile = File(filesDir, "temp_input.db")
                contentResolver.openInputStream(selectedInputUri!!)?.use { input ->
                    FileOutputStream(internalInputFile).use { output -> input.copyTo(output) }
                }
                
                val internalOutputFile = File(filesDir, "temp_output.db")
                if (internalOutputFile.exists()) internalOutputFile.delete()

                withContext(Dispatchers.Main) { tvStatus.text = "正在计算 (请查看 Logcat 进度)..." }

                // 2. 调用 C 语言核心计算 (耗时操作)
                val resultMsg = runCalculationNative(internalInputFile.absolutePath, internalOutputFile.absolutePath)

                // 3. 计算完成，触发系统文件保存对话框
                withContext(Dispatchers.Main) {
                    tvStatus.text = resultMsg
                    if (resultMsg.contains("成功")) {
                        createOutputFile.launch("pedigree_results.db")
                    }
                }
                
                internalInputFile.delete()

            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvStatus.text = "发生错误: ${e.message}" }
            }
        }
    }

    private fun saveOutputToUri(destUri: Uri) {
        val internalOutputFile = File(filesDir, "temp_output.db")
        if (!internalOutputFile.exists()) return
        contentResolver.openOutputStream(destUri)?.use { output ->
            internalOutputFile.inputStream().use { input -> input.copyTo(output) }
        }
        internalOutputFile.delete() 
        Toast.makeText(this, "结果已成功导出！", Toast.LENGTH_LONG).show()
        tvStatus.text = "计算并导出完成。"
    }
}