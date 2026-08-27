package com.example.pedigreeapp

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {
    
    // JNI 原生计算方法
    external fun runCalculationNative(inputPath: String, outputPath: String): String

    // JNI 原生配对亲缘查询：返回 A(id1, id2)，个体不存在时返回 NaN
    external fun queryRelationshipNative(inputPath: String, id1: Int, id2: Int): Double

    companion object {
        init { System.loadLibrary("pedigree_native") }
        private const val PAGE_SIZE = 200   /* 关系浏览分页大小 */
    }

    // ==========================================
    // 1. 核心状态与 UI 变量 (与 activity_main.xml 完美对应)
    // ==========================================
    private lateinit var dbHelper: PedigreeDbHelper
    private lateinit var adapter: PedigreeAdapter
    private lateinit var inbreedingAdapter: InbreedingAdapter
    private val relationshipAdapter = RelationshipAdapter()
    private var showingRelationships = false   /* 当前是否处于关系浏览模式 */
    private lateinit var recyclerView: RecyclerView
    
    // 【修复】：声明 XML 中存在的控件
    private lateinit var tvStatus: TextView
    private lateinit var tvDataCount: TextView
    private lateinit var btnSwitchBack: Button
    private lateinit var btnCalculate: Button
    private lateinit var btnViewResults: Button

    // 数据源状态管理
    private var currentInputDbPath: String = ""
    private var isUsingExternalDb = false

    /** 当前计算/展示所用的数据源（外部库或内部库），统一读取入口 */
    private val activeInputDbPath: String
        get() = if (isUsingExternalDb) currentInputDbPath else getDatabasePath("pedigree_input.db").absolutePath

    // ==========================================
    // 2. 文件选择器 (SAF) 注册
    // ==========================================
    private val pickImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { handleImportExternalData(it) }
    }
    private val pickOpenDbLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { switchToExternalDb(it) }
    }
    private val createResultLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-sqlite3")) { uri ->
        uri?.let { exportFileToUri(File(filesDir, "pedigree_results.db"), it, "结果数据") }
    }
    private val createInputDbLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-sqlite3")) { uri ->
        uri?.let { exportFileToUri(File(filesDir, "pedigree_input.db"), it, "输入数据") }
    }

    // ==========================================
    // 3. 生命周期与初始化
    // ==========================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        dbHelper = PedigreeDbHelper(this)
        currentInputDbPath = getDatabasePath("pedigree_input.db").absolutePath
        
        initViews()
        setupRecyclerView()
        showingRelationships = false   // 启动时回到系谱数据展示模式
        
        // 首次运行插入测试数据
        if (dbHelper.getAllRecords().isEmpty()) {
            dbHelper.insertRecord(1, 0, 0)
            dbHelper.insertRecord(2, 1, 0)
            dbHelper.insertRecord(3, 1, 2)
            dbHelper.insertRecord(4, 3, 2)
            adapter.updateData(dbHelper.getAllRecords())
        }
        updateDataCount()
    }

    private fun initViews() {
        // 1. 初始化 Toolbar
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        // 2. 【核心修复】：从 XML 中 findViewById 获取真实实例，彻底杜绝 ClassCastException 和幽灵控件
        tvStatus = findViewById(R.id.tvStatus)
        tvDataCount = findViewById(R.id.tvDataCount)
        recyclerView = findViewById(R.id.recyclerView)
        
        // 3. 【新增绑定】：激活 XML 中定义的控制面板按钮
        btnSwitchBack = findViewById(R.id.btnSwitchBack)
        btnCalculate = findViewById(R.id.btnCalculate)
        btnViewResults = findViewById(R.id.btnViewResults)

        // 4. 绑定按钮点击事件，使 XML 布局真正起作用
        btnSwitchBack.setOnClickListener { switchBackToInternalDb() }
        btnCalculate.setOnClickListener { startCalculation() }
        btnViewResults.setOnClickListener { viewResults() }

        // 5. 底部导航栏点击事件
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    recyclerView.adapter = adapter
                    showingRelationships = false   // 离开关系浏览模式
                    supportActionBar?.title = "系谱数据"
                    tvStatus.text = "就绪" // 切换回首页时重置状态提示
                    true
                }
                R.id.nav_calculate -> {
                    showCalculationDialog()
                    true
                }
                R.id.nav_results -> {
                    viewResults()
                    true
                }
                R.id.nav_settings -> {
                    showSettingsDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = PedigreeAdapter(emptyList())
        inbreedingAdapter = InbreedingAdapter(emptyList())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        adapter.updateData(dbHelper.getAllRecords())
    }

    // ==========================================
    // 4. 数据管理逻辑
    // ==========================================
    private fun updateDataCount() {
        lifecycleScope.launch(Dispatchers.IO) {
            val count = try {
                val db = SQLiteDatabase.openDatabase(activeInputDbPath, null, SQLiteDatabase.OPEN_READONLY)
                val cursor = db.rawQuery("SELECT COUNT(*) FROM Pedigree", null)
                cursor.moveToFirst()
                val c = cursor.getInt(0)
                cursor.close()
                db.close()
                c
            } catch (e: Exception) { 0 }
            
            withContext(Dispatchers.Main) {
                tvDataCount.text = "当前数据条数: $count" +
                        if (isUsingExternalDb) " (外部数据库)" else ""
            }
        }
    }

    private fun showAddDataDialog() {
        val inputId = EditText(this).apply { hint = "个体 ID" }
        val inputSire = EditText(this).apply { hint = "父亲 ID (无则填0)" }
        val inputDam = EditText(this).apply { hint = "母亲 ID (无则填0)" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
            addView(inputId)
            addView(inputSire)
            addView(inputDam)
        }
        AlertDialog.Builder(this)
            .setTitle("追加系谱数据")
            .setView(layout)
            .setPositiveButton("添加") { _, _ ->
                val id = inputId.text.toString().toIntOrNull() ?: 0
                val sire = inputSire.text.toString().toIntOrNull() ?: 0
                val dam = inputDam.text.toString().toIntOrNull() ?: 0
                if (id > 0) {
                    dbHelper.insertRecord(id, sire, dam)
                    adapter.updateData(dbHelper.getAllRecords())
                    updateDataCount()
                    Toast.makeText(this, "添加成功", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun handleImportExternalData(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(filesDir, "temp_import.csv")
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                val db = SQLiteDatabase.openDatabase(currentInputDbPath, null, SQLiteDatabase.OPEN_READWRITE)
                BufferedReader(InputStreamReader(tempFile.inputStream())).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val parts = line?.split(",") ?: continue
                        if (parts.size >= 3) {
                            val id = parts[0].trim().toIntOrNull() ?: continue
                            val sire = parts[1].trim().toIntOrNull() ?: 0
                            val dam = parts[2].trim().toIntOrNull() ?: 0
                            val cv = ContentValues().apply {
                                put("ID", id)
                                put("SireID", sire)
                                put("DamID", dam)
                            }
                            db.insertWithOnConflict("Pedigree", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                        }
                    }
                }
                db.close()
                tempFile.delete()
                withContext(Dispatchers.Main) {
                    updateDataCount()
                    adapter.updateData(dbHelper.getAllRecords())
                    Toast.makeText(this@MainActivity, "导入成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun switchToExternalDb(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(filesDir, "temp_external.db")
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                // 校验外部库是否包含可用的 Pedigree 表，避免切换到无效文件
                var externalCount = 0
                try {
                    val db = SQLiteDatabase.openDatabase(tempFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    val cursor = db.rawQuery("SELECT COUNT(*) FROM Pedigree", null)
                    cursor.moveToFirst()
                    externalCount = cursor.getInt(0)
                    cursor.close()
                    db.close()
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity,
                            "无效的系谱数据库：缺少 Pedigree 表", Toast.LENGTH_LONG).show()
                    }
                    tempFile.delete()
                    return@launch
                }

                currentInputDbPath = tempFile.absolutePath
                isUsingExternalDb = true
                withContext(Dispatchers.Main) {
                    btnSwitchBack.visibility = View.VISIBLE // 显示切回按钮
                    tvStatus.text = "已切换至外部数据库"
                    updateDataCount()
                    // 从当前生效的外部数据库读取数据展示
                    val list = mutableListOf<PedigreeRecord>()
                    try {
                        val db = SQLiteDatabase.openDatabase(currentInputDbPath, null, SQLiteDatabase.OPEN_READONLY)
                        val cursor = db.rawQuery(
                            "SELECT ID, SireID, DamID FROM Pedigree ORDER BY ID ASC", null)
                        if (cursor.moveToFirst()) {
                            do {
                                list.add(PedigreeRecord(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2)))
                            } while (cursor.moveToNext())
                        }
                        cursor.close()
                        db.close()
                    } catch (_: Exception) { }
                    adapter.updateData(list)
                    Toast.makeText(this@MainActivity,
                        "已切换至外部数据库（$externalCount 条）", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "打开失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 【新增】：补全切回内部数据库的逻辑，形成闭环
    private fun switchBackToInternalDb() {
        currentInputDbPath = getDatabasePath("pedigree_input.db").absolutePath
        isUsingExternalDb = false
        btnSwitchBack.visibility = View.GONE
        tvStatus.text = "已切回内部数据库"
        updateDataCount()
        adapter.updateData(dbHelper.getAllRecords())
        Toast.makeText(this, "已切回内部默认数据库", Toast.LENGTH_SHORT).show()
    }

    // ==========================================
    // 5. 计算与结果展示逻辑
    // ==========================================
    private fun startCalculation() {
        tvStatus.text = "计算中，请稍候..."
        btnCalculate.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            // 关键优化：复制一份数据库文件进行计算，避免 C 层和 Kotlin 层同时读写同一个文件导致锁崩溃
            val tempCalcDb = File(filesDir, "temp_calc.db")
            File(activeInputDbPath).copyTo(tempCalcDb, overwrite = true)
            val outputPath = File(filesDir, "pedigree_results.db").absolutePath
            File(outputPath).delete()
            
            val resultMsg = runCalculationNative(tempCalcDb.absolutePath, outputPath)
            tempCalcDb.delete() // 计算完成后删除临时副本
            
            withContext(Dispatchers.Main) {
                showingRelationships = false   // 新一轮计算后回到结果浏览模式
                tvStatus.text = resultMsg
                btnCalculate.isEnabled = true
                Toast.makeText(this@MainActivity, resultMsg, Toast.LENGTH_SHORT).show()
                // 计算完成后自动跳转到结果页
                findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_results
            }
        }
    }

    private fun viewResults() {
        val resultPath = File(filesDir, "pedigree_results.db").absolutePath
        if (!File(resultPath).exists()) {
            tvStatus.text = "请先进行计算"
            Toast.makeText(this, "请先进行计算", Toast.LENGTH_SHORT).show()
            return
        }
        
        tvStatus.text = "正在加载结果..."
        lifecycleScope.launch(Dispatchers.IO) {
            val inbreedingList = mutableListOf<Pair<Int, Double>>()
            var relationCount = 0
            try {
                val db = SQLiteDatabase.openDatabase(resultPath, null, SQLiteDatabase.OPEN_READONLY)
                val cursor = db.rawQuery("SELECT ID, F FROM Inbreeding", null)
                if (cursor.moveToFirst()) {
                    do {
                        inbreedingList.add(cursor.getInt(0) to cursor.getDouble(1))
                    } while (cursor.moveToNext())
                }
                cursor.close()
                // 统计成对亲缘关系数量，供界面提示
                try {
                    val rc = db.rawQuery("SELECT COUNT(*) FROM Relationship", null)
                    rc.moveToFirst()
                    relationCount = rc.getInt(0)
                    rc.close()
                } catch (_: Exception) { }
                db.close()
            } catch (e: Exception) {
                // 表不存在时忽略
            }
            
            withContext(Dispatchers.Main) {
                showingRelationships = false   // 离开关系浏览模式
                inbreedingAdapter.updateData(inbreedingList)
                recyclerView.adapter = inbreedingAdapter
                supportActionBar?.title = "近交系数结果"
                tvStatus.text = "查看结果: 近交系数 (共 ${inbreedingList.size} 条)" +
                        if (relationCount > 0) "；可在「计算」菜单中浏览 $relationCount 条亲缘关系" else ""
            }
        }
    }

    private fun exportFileToUri(srcFile: File, uri: Uri, name: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    srcFile.inputStream().use { input -> input.copyTo(output) }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "$name 导出成功", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "$name 导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showCalculationDialog() {
        val options = arrayOf(
            "开始计算",
            "追加系谱数据",
            "导入外部数据 (CSV)",
            "使用外部数据库",
            "导出输入数据",
            "浏览亲缘关系 (A 矩阵)",
            "查询配对亲缘"
        )
        AlertDialog.Builder(this)
            .setTitle("计算与数据管理")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startCalculation()
                    1 -> showAddDataDialog()
                    2 -> pickImportLauncher.launch(arrayOf("*/*"))
                    3 -> pickOpenDbLauncher.launch(arrayOf("*/*"))
                    4 -> createInputDbLauncher.launch("pedigree_input.db")
                    5 -> browseRelationships()
                    6 -> showPairQueryDialog()
                }
            }
            .show()
    }

    // ==========================================
    // 6. 亲缘关系浏览与配对查询
    // ==========================================
    /** 分页加载结果库中的成对亲缘关系，复用 RelationshipAdapter 的加载更多能力 */
    private fun browseRelationships() {
        val resultPath = File(filesDir, "pedigree_results.db").absolutePath
        if (!File(resultPath).exists()) {
            Toast.makeText(this, "请先进行计算", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var rows: List<Triple<Int, Int, Double>>? = null
            try {
                // itemCount 含 1 个 loading 行，实际已加载数据条数需减 1
                val loadedCount = (relationshipAdapter.itemCount - 1).coerceAtLeast(0)
                val db = SQLiteDatabase.openDatabase(resultPath, null, SQLiteDatabase.OPEN_READONLY)
                val cursor = db.rawQuery(
                    "SELECT ID1, ID2, A FROM Relationship ORDER BY ID1 ASC, ID2 ASC LIMIT ? OFFSET ?",
                    arrayOf(PAGE_SIZE.toString(), loadedCount.toString()))
                val list = mutableListOf<Triple<Int, Int, Double>>()
                if (cursor.moveToFirst()) {
                    do {
                        list.add(Triple(cursor.getInt(0), cursor.getInt(1), cursor.getDouble(2)))
                    } while (cursor.moveToNext())
                }
                cursor.close()
                db.close()
                rows = list
            } catch (_: Exception) { }

            withContext(Dispatchers.Main) {
                if (rows == null) {
                    Toast.makeText(this@MainActivity,
                        "结果库中没有亲缘关系数据", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                if (!showingRelationships) {
                    relationshipAdapter.clearData()   /* 首次进入时清掉占位的 loading 行 */
                }
                showingRelationships = true
                recyclerView.adapter = relationshipAdapter
                supportActionBar?.title = "亲缘关系 (A 矩阵)"
                if (rows.isNotEmpty()) {
                    relationshipAdapter.addData(rows)
                    recyclerView.scrollToPosition(relationshipAdapter.itemCount - 1)
                }
                tvStatus.text = "亲缘关系: 已显示 ${relationshipAdapter.itemCount - 1} 对"
                when {
                    relationshipAdapter.itemCount - 1 == 0 ->
                        Toast.makeText(this@MainActivity,
                            "种群较大或无有效关系对，未导出成对关系表", Toast.LENGTH_LONG).show()
                    rows.size < PAGE_SIZE ->
                        Toast.makeText(this@MainActivity,
                            "没有更多数据了", Toast.LENGTH_SHORT).show()
                    else ->
                        Toast.makeText(this@MainActivity,
                            "再次点击「浏览亲缘关系」可加载更多", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 配对亲缘查询：直接调用 C 引擎计算 A(id1, id2)，无需先全量计算 */
    private fun showPairQueryDialog() {
        val input1 = EditText(this).apply { hint = "个体 ID 1" }
        val input2 = EditText(this).apply { hint = "个体 ID 2" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 30)
            addView(input1)
            addView(input2)
        }
        AlertDialog.Builder(this)
            .setTitle("查询配对亲缘关系")
            .setMessage("将计算两只动物之间的加性亲缘关系系数 A 值")
            .setView(layout)
            .setPositiveButton("查询") { _, _ ->
                val id1 = input1.text.toString().toIntOrNull()
                val id2 = input2.text.toString().toIntOrNull()
                if (id1 == null || id2 == null || id1 <= 0 || id2 <= 0) {
                    Toast.makeText(this, "请输入有效的正整数 ID", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                tvStatus.text = "正在计算 $id1 与 $id2 的亲缘关系..."
                lifecycleScope.launch(Dispatchers.IO) {
                    val a = queryRelationshipNative(activeInputDbPath, id1, id2)
                    withContext(Dispatchers.Main) {
                        if (a.isNaN()) {
                            tvStatus.text = "查询失败：个体不存在或数据无效"
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("查询失败")
                                .setMessage("ID $id1 或 $id2 不在当前系谱中")
                                .setPositiveButton("知道了", null)
                                .show()
                        } else {
                            val text = "A($id1, $id2) = %.6f\n近交相似度约 %.4f%%".format(a, a * 50.0)
                            tvStatus.text = text.replace("\n", " ")
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("查询结果")
                                .setMessage(text)
                                .setPositiveButton("知道了", null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSettingsDialog() {
        val options = arrayOf("关于应用", "清空内部数据 (慎用)", "导出结果数据")
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this, "动物系谱分析系统 v6.0\n基于 Henderson 算法", Toast.LENGTH_LONG).show()
                    1 -> {
                        AlertDialog.Builder(this)
                            .setTitle("确认清空")
                            .setMessage("确定要清空所有内部系谱数据吗？此操作不可逆！")
                            .setPositiveButton("确定") { _, _ ->
                                dbHelper.writableDatabase.delete("Pedigree", null, null)
                                adapter.updateData(emptyList())
                                updateDataCount()
                                tvStatus.text = "数据已清空"
                                Toast.makeText(this, "数据已清空", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    2 -> createResultLauncher.launch("pedigree_results.db")
                }
            }
            .show()
    }
}
