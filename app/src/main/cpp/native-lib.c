#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sqlite3.h>
#include <stdbool.h>

// ==========================================
// 数据结构 (稀疏矩阵 SoA 布局)
// ==========================================
typedef struct {
    int col_idx;
    double value;
} SparseEntry;

typedef struct {
    int id;
    int sire_idx;
    int dam_idx;
    int sort_order;
    double F;
    SparseEntry *row_entries;
    int row_count;
} Animal;

// ==========================================
// 二分查找 (坐标压缩后 ID→索引)
// ==========================================
static int cmp_int(const void *a, const void *b) {
    return (*(const int*)a - *(const int*)b);
}

static int get_idx(int id, int *all_ids, int n) {
    if (id <= 0) return -1;
    int *res = (int*)bsearch(&id, all_ids, n, sizeof(int), cmp_int);
    return res ? (int)(res - all_ids) : -1;
}

// ==========================================
// 拓扑排序 (Kahn 算法)
// ==========================================
static void topo_sort(Animal *animals, int *sorted, int n) {
    int *in_degree = (int*)calloc(n, sizeof(int));
    int *adj_head = (int*)calloc(n, sizeof(int));
    
    typedef struct { int child; int next; } Edge;
    Edge *edges = (Edge*)malloc(2 * n * sizeof(Edge));
    int edge_cnt = 0;
    
    for (int i = 0; i < n; i++) {
        if (animals[i].sire_idx != -1) {
            in_degree[i]++;
            edges[edge_cnt++] = (Edge){i, adj_head[animals[i].sire_idx]};
            adj_head[animals[i].sire_idx] = edge_cnt - 1;
        }
        if (animals[i].dam_idx != -1 && animals[i].dam_idx != animals[i].sire_idx) {
            in_degree[i]++;
            edges[edge_cnt++] = (Edge){i, adj_head[animals[i].dam_idx]};
            adj_head[animals[i].dam_idx] = edge_cnt - 1;
        }
    }
    
    int *queue = (int*)malloc(n * sizeof(int));
    int front = 0, back = 0, sorted_n = 0;
    
    for (int i = 0; i < n; i++) {
        if (in_degree[i] == 0) queue[back++] = i;
    }
    
    while (front < back) {
        int p = queue[front++];
        sorted[sorted_n++] = p;
        for (int e = adj_head[p]; e != -1; e = edges[e].next) {
            int c = edges[e].child;
            if (--in_degree[c] == 0) queue[back++] = c;
        }
    }
    
    // 处理环路
    if (sorted_n != n) {
        for (int i = 0; i < n; i++) {
            if (in_degree[i] > 0) sorted[sorted_n++] = i;
        }
    }
    
    free(in_degree); free(adj_head); free(edges); free(queue);
}

// ==========================================
// 核心计算：二路归并 (O(N) 无移动)
// ==========================================
static void calculate_relationships(Animal *animals, int n) {
    SparseEntry *merge_buf = (SparseEntry*)malloc(n * sizeof(SparseEntry));
    
    for (int i = 0; i < n; i++) {
        int idx = animals[i].sort_order >= 0 ? animals[i].sort_order : i;
        Animal *curr = &animals[idx];
        int s = curr->sire_idx;
        int d = curr->dam_idx;
        
        Animal *sire = (s != -1) ? &animals[s] : NULL;
        Animal *dam = (d != -1) ? &animals[d] : NULL;
        
        int n1 = sire ? sire->row_count : 0;
        int n2 = dam ? dam->row_count : 0;
        
        double s_val = (s != -1) ? 0.5 * (1.0 + animals[s].F) : 0.0;
        double d_val = (d != -1) ? 0.5 * (1.0 + animals[d].F) : 0.0;
        
        int p1 = 0, p2 = 0, out_cnt = 0;
        double a_sd = 0.0;
        bool s_added = false, d_added = false;
        
        SparseEntry *s1 = sire ? sire->row_entries : NULL;
        SparseEntry *s2 = dam ? dam->row_entries : NULL;
        
        // 二路归并
        while (p1 < n1 && p2 < n2) {
            int c1 = s1[p1].col_idx;
            int c2 = s2[p2].col_idx;
            int mc = (c1 < c2) ? c1 : c2;
            double sum = 0.0;
            
            if (c1 == mc) {
                sum += 0.5 * s1[p1].value;
                if (mc == s) s_added = true;
                if (mc == d) d_added = true;
                if (s != -1 && d != -1 && mc == (s < d ? d : s)) a_sd = s1[p1].value;
                p1++;
            }
            if (c2 == mc) {
                sum += 0.5 * s2[p2].value;
                if (mc == s) s_added = true;
                if (mc == d) d_added = true;
                if (s != -1 && d != -1 && mc == (d < s ? d : s) && a_sd == 0.0) a_sd = s2[p2].value;
                p2++;
            }
            
            if (sum > 1e-12) merge_buf[out_cnt++] = (SparseEntry){mc, sum};
        }
        
        while (p1 < n1) {
            int c1 = s1[p1].col_idx;
            double sum = 0.5 * s1[p1].value;
            if (c1 == s) s_added = true;
            if (c1 == d) d_added = true;
            if (sum > 1e-12) merge_buf[out_cnt++] = (SparseEntry){c1, sum};
            p1++;
        }
        
        while (p2 < n2) {
            int c2 = s2[p2].col_idx;
            double sum = 0.5 * s2[p2].value;
            if (c2 == s) s_added = true;
            if (c2 == d) d_added = true;
            if (sum > 1e-12) merge_buf[out_cnt++] = (SparseEntry){c2, sum};
            p2++;
        }
        
        // 延迟插入父母
        if (s != -1 && !s_added) {
            int pos = out_cnt;
            while (pos > 0 && merge_buf[pos-1].col_idx > s) {
                merge_buf[pos] = merge_buf[pos-1];
                pos--;
            }
            merge_buf[pos] = (SparseEntry){s, s_val};
            out_cnt++;
        }
        if (d != -1 && !d_added && d != s) {
            int pos = out_cnt;
            while (pos > 0 && merge_buf[pos-1].col_idx > d) {
                merge_buf[pos] = merge_buf[pos-1];
                pos--;
            }
            merge_buf[pos] = (SparseEntry){d, d_val};
            out_cnt++;
        }
        
        curr->F = (s != -1 && d != -1) ? (0.5 * a_sd) : 0.0;
        curr->row_count = out_cnt;
        
        if (out_cnt > 0) {
            curr->row_entries = (SparseEntry*)malloc(out_cnt * sizeof(SparseEntry));
            memcpy(curr->row_entries, merge_buf, out_cnt * sizeof(SparseEntry));
        }
    }
    
    free(merge_buf);
}

// ==========================================
// JNI 入口
// ==========================================
JNIEXPORT jint JNICALL
Java_com_example_pedigreeapp_MainActivity_runPedigreeNative(
    JNIEnv *env, jobject thiz,
    jstring input_db, jstring output_db, jstring log_file) {
    
    const char *in_path = (*env)->GetStringUTFChars(env, input_db, NULL);
    const char *out_path = (*env)->GetStringUTFChars(env, output_db, NULL);
    const char *log_path = (*env)->GetStringUTFChars(env, log_file, NULL);
    
    FILE *log_fp = fopen(log_path, "w");
    if (!log_fp) log_fp = stdout;
    
    time_t start_time = time(NULL);
    fprintf(log_fp, "系谱分析 v6.0 开始\n");
    fprintf(log_fp, "输入：%s\n", in_path);
    fprintf(log_fp, "输出：%s\n", out_path);
    
    sqlite3 *db_in;
    if (sqlite3_open_v2(in_path, &db_in, SQLITE_OPEN_READONLY, NULL) != SQLITE_OK) {
        fprintf(log_fp, "无法打开输入数据库：%s\n", sqlite3_errmsg(db_in));
        goto cleanup;
    }
    
    // 读取原始数据
    int raw_cap = 1024;
    typedef struct { int id, sire, dam; } RawRec;
    RawRec *raw = (RawRec*)malloc(raw_cap * sizeof(RawRec));
    int raw_count = 0;
    
    const char *sql = "SELECT ID, SireID, DamID FROM Pedigree";
    sqlite3_stmt *stmt;
    if (sqlite3_prepare_v2(db_in, sql, -1, &stmt, NULL) != SQLITE_OK) {
        fprintf(log_fp, "SQL 错误：%s\n", sqlite3_errmsg(db_in));
        sqlite3_close(db_in);
        goto cleanup;
    }
    
    while (sqlite3_step(stmt) == SQLITE_ROW) {
        if (raw_count >= raw_cap) {
            raw_cap *= 2;
            raw = (RawRec*)realloc(raw, raw_cap * sizeof(RawRec));
        }
        int id = sqlite3_column_int(stmt, 0);
        if (id > 0) {
            raw[raw_count].id = id;
            raw[raw_count].sire = sqlite3_column_int(stmt, 1);
            raw[raw_count].dam = sqlite3_column_int(stmt, 2);
            raw_count++;
        }
    }
    sqlite3_finalize(stmt);
    sqlite3_close(db_in);
    
    fprintf(log_fp, "读取 %d 条记录\n", raw_count);
    
    // 坐标压缩
    int *all_ids = (int*)malloc(raw_count * 3 * sizeof(int));
    int all_n = 0;
    for (int i = 0; i < raw_count; i++) {
        all_ids[all_n++] = raw[i].id;
        if (raw[i].sire > 0) all_ids[all_n++] = raw[i].sire;
        if (raw[i].dam > 0) all_ids[all_n++] = raw[i].dam;
    }
    qsort(all_ids, all_n, sizeof(int), cmp_int);
    
    int n = 0;
    for (int i = 0; i < all_n; i++) {
        if (i == 0 || all_ids[i] != all_ids[i-1]) {
            all_ids[n++] = all_ids[i];
        }
    }
    
    fprintf(log_fp, "唯一 ID 数量：%d\n", n);
    
    // 初始化动物数组
    Animal *animals = (Animal*)calloc(n, sizeof(Animal));
    int *sorted = (int*)malloc(n * sizeof(int));
    
    for (int i = 0; i < n; i++) {
        animals[i].id = all_ids[i];
        animals[i].sire_idx = -1;
        animals[i].dam_idx = -1;
        animals[i].sort_order = -1;
    }
    
    for (int i = 0; i < raw_count; i++) {
        int idx = get_idx(raw[i].id, all_ids, n);
        if (idx != -1) {
            animals[idx].sire_idx = get_idx(raw[i].sire, all_ids, n);
            animals[idx].dam_idx = get_idx(raw[i].dam, all_ids, n);
        }
    }
    free(raw);
    
    // 拓扑排序 + 计算
    topo_sort(animals, sorted, n);
    for (int i = 0; i < n; i++) animals[i].sort_order = i;  // 简化：假设无环路
    calculate_relationships(animals, n);
    
    // 写入输出
    sqlite3 *db_out;
    if (sqlite3_open(out_path, &db_out) != SQLITE_OK) {
        fprintf(log_fp, "无法创建输出数据库\n");
        goto cleanup;
    }
    
    sqlite3_exec(db_out, "PRAGMA synchronous=OFF; PRAGMA journal_mode=OFF;", NULL, NULL, NULL);
    sqlite3_exec(db_out, "DROP TABLE IF EXISTS Inbreeding; DROP TABLE IF EXISTS RelationshipMatrix;", NULL, NULL, NULL);
    sqlite3_exec(db_out, "CREATE TABLE Inbreeding (ID INTEGER PRIMARY KEY, F REAL);", NULL, NULL, NULL);
    sqlite3_exec(db_out, "CREATE TABLE RelationshipMatrix (ID1 INTEGER, ID2 INTEGER, A REAL);", NULL, NULL, NULL);
    
    sqlite3_exec(db_out, "BEGIN TRANSACTION;", NULL, NULL, NULL);
    
    // 写入近交系数
    sqlite3_stmt *stmt_f;
    sqlite3_prepare_v2(db_out, "INSERT INTO Inbreeding (ID, F) VALUES (?, ?);", -1, &stmt_f, NULL);
    for (int i = 0; i < n; i++) {
        sqlite3_bind_int(stmt_f, 1, animals[i].id);
        sqlite3_bind_double(stmt_f, 2, animals[i].F);
        sqlite3_step(stmt_f);
        sqlite3_reset(stmt_f);
    }
    sqlite3_finalize(stmt_f);
    
    // 写入关系矩阵
    sqlite3_stmt *stmt_a;
    sqlite3_prepare_v2(db_out, "INSERT INTO RelationshipMatrix (ID1, ID2, A) VALUES (?, ?, ?);", -1, &stmt_a, NULL);
    long long rel_count = 0;
    
    for (int i = 0; i < n; i++) {
        // 对角线
        sqlite3_bind_int(stmt_a, 1, animals[i].id);
        sqlite3_bind_int(stmt_a, 2, animals[i].id);
        sqlite3_bind_double(stmt_a, 3, 1.0 + animals[i].F);
        sqlite3_step(stmt_a);
        sqlite3_reset(stmt_a);
        rel_count++;
        
        // 非对角线
        for (int k = 0; k < animals[i].row_count; k++) {
            int j = animals[i].row_entries[k].col_idx;
            double val = animals[i].row_entries[k].value;
            sqlite3_bind_int(stmt_a, 1, animals[i].id);
            sqlite3_bind_int(stmt_a, 2, all_ids[j]);
            sqlite3_bind_double(stmt_a, 3, val);
            sqlite3_step(stmt_a);
            sqlite3_reset(stmt_a);
            rel_count++;
        }
    }
    
    sqlite3_finalize(stmt_a);
    sqlite3_exec(db_out, "COMMIT;", NULL, NULL, NULL);
    sqlite3_close(db_out);
    
    time_t end_time = time(NULL);
    fprintf(log_fp, "写入完成，关系记录数：%lld\n", rel_count);
    fprintf(log_fp, "总耗时：%ld 秒\n", (long)(end_time - start_time));
    
    // 统计摘要
    double max_f = -1, min_f = 2, sum_f = 0;
    int max_id = -1, min_id = -1;
    for (int i = 0; i < n; i++) {
        double f = animals[i].F;
        sum_f += f;
        if (f > max_f) { max_f = f; max_id = animals[i].id; }
        if (f < min_f) { min_f = f; min_id = animals[i].id; }
    }
    fprintf(log_fp, "\n最高近交系数：ID %d, F = %.4f\n", max_id, max_f);
    fprintf(log_fp, "最低近交系数：ID %d, F = %.4f\n", min_id, min_f);
    fprintf(log_fp, "平均近交系数：%.4f\n", sum_f / n);
    
cleanup:
    // 释放内存
    for (int i = 0; i < n; i++) free(animals[i].row_entries);
    free(animals); free(sorted); free(all_ids);
    
    (*env)->ReleaseStringUTFChars(env, input_db, in_path);
    (*env)->ReleaseStringUTFChars(env, output_db, out_path);
    (*env)->ReleaseStringUTFChars(env, log_file, log_path);
    
    if (log_fp != stdout) fclose(log_fp);
    
    return 0;
}