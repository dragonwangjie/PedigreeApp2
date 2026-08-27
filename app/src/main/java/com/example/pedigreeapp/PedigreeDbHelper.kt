package com.example.pedigreeapp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class PedigreeDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "pedigree_input.db"
        const val DATABASE_VERSION = 1
        const val TABLE_NAME = "Pedigree"
        const val COL_ID = "ID"
        const val COL_SIRE = "SireID"
        const val COL_DAM = "DamID"
    }

    // 如果数据库不存在，系统会自动调用此方法创建表和结构
    override fun onCreate(db: SQLiteDatabase) {
        val createTable = "CREATE TABLE $TABLE_NAME (" +
                "$COL_ID INTEGER PRIMARY KEY, " +
                "$COL_SIRE INTEGER, " +
                "$COL_DAM INTEGER)"
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    // 追加数据
    fun insertRecord(id: Int, sireId: Int, damId: Int): Boolean {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, id)
            put(COL_SIRE, sireId)
            put(COL_DAM, damId)
        }
        return db.insert(TABLE_NAME, null, values) != -1L
    }

    // 获取所有数据用于界面显示
    fun getAllRecords(): List<PedigreeRecord> {
        val list = mutableListOf<PedigreeRecord>()
        val db = readableDatabase
        val cursor = db.query(TABLE_NAME, null, null, null, null, null, "$COL_ID ASC")
        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getInt(cursor.getColumnIndexOrThrow(COL_ID))
                val sire = cursor.getInt(cursor.getColumnIndexOrThrow(COL_SIRE))
                val dam = cursor.getInt(cursor.getColumnIndexOrThrow(COL_DAM))
                list.add(PedigreeRecord(id, sire, dam))
            } while (cursor.moveToNext())
        }
        cursor.close()
        return list
    }
}