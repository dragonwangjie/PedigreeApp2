/*
 * PedigreeApp2 核心计算引擎
 *
 * 实现 Henderson 混合模型下的分子亲缘矩阵 A 的稀疏表格法 (Tabular Method)：
 *   - 从输入 SQLite 库读取 Pedigree(ID, SireID, DamID)
 *   - 坐标压缩（含隐式补齐缺失的双亲为奠基者）
 *   - Kahn 拓扑排序保证父母先于后代参与计算
 *   - 逐行归并双亲的稀疏行，零值剪枝，内存池统一分配
 *   - 输出 Inbreeding(ID, F)；小种群时额外导出 Relationship(ID1, ID2, A)
 *
 * 另提供 queryRelationshipNative 用于任意两只动物的配对亲缘查询。
 */
#define _POSIX_C_SOURCE 199309L   /* clock_gettime / CLOCK_MONOTONIC */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <math.h>
#include <time.h>
#include <limits.h>
#include <pthread.h>

#include <jni.h>
#include <sqlite3.h>

// ==========================================
// 日志宏：置空以消除对系统 liblog 的符号依赖
// （APK 内的 .so 无法隐式解析未声明的 __android_log_print，
//   会导致 dlopen 失败；如需 logcat 日志请显式链接 -llog）
// ==========================================
#define LOG_TAG "PedigreeCalc"
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)

// ==========================================
// 常量与阈值
// ==========================================
#define EPS_ZERO             1e-14                  /* 稀疏行零值剪枝阈值 */
#define EPS_REL              1e-12                  /* 导出关系对时的有效值阈值 */
#define MAX_REL_EXPORT_N     300                    /* 种群规模超过此值不导出成对关系表 (O(N^2)) */
#define MEMORY_LIMIT_ENTRIES (16LL * 1024 * 1024)   /* 稀疏元素总量上限，防御性保护 */
#define POOL_CHUNK_ENTRIES   65536                  /* 内存池块大小 */

// ==========================================
// 数据结构定义
// ==========================================
typedef struct { int col_idx; double value; } SparseEntry;

typedef struct {
    int sire_pos;              /* 双亲在拓扑序中的位置，-1 表示未知 */
    int dam_pos;
    int row_count;             /* 本行稀疏元素个数（含对角元）*/
    double F;                  /* 近交系数 */
    SparseEntry *row_entries;  /* 指向内存池中的行数据（按列号升序）*/
} CalcAnimal;

typedef struct { int id, sire_id, dam_id; } RawRec;

typedef struct PoolChunk {
    SparseEntry *data;
    size_t capacity, used;
    struct PoolChunk *next;
} PoolChunk;

typedef struct {
    PoolChunk *head, *current;
    size_t total_used, chunk_size;
} SparsePool;

// ==========================================
// 全局变量（由 g_calc_mutex 保护，不可重入）
// ==========================================
static pthread_mutex_t g_calc_mutex = PTHREAD_MUTEX_INITIALIZER;

static CalcAnimal *population = NULL;   /* 以拓扑序为下标 */
static int         pop_size  = 0;
static int        *all_ids        = NULL;  /* 压缩后的连续节点 id 表（升序）*/
static int        *sorted_indices = NULL;  /* 拓扑序位置 -> 节点下标 */
static int        *node_sire      = NULL;  /* 节点下标 -> 父节点下标（-1 未知）*/
static int        *node_dam       = NULL;  /* 节点下标 -> 母节点下标（-1 未知）*/
static SparsePool  pool;
static SparseEntry *merge_buf = NULL;     /* 归并缓冲区 */
static size_t      merge_cap = 0;

// ==========================================
// 内存释放
// ==========================================
static void cleanup_resources(void) {
    PoolChunk *chunk = pool.head;
    while (chunk != NULL) {
        PoolChunk *next = chunk->next;
        free(chunk->data);
        free(chunk);
        chunk = next;
    }
    memset(&pool, 0, sizeof(pool));

    free(population);     population     = NULL;
    free(all_ids);        all_ids        = NULL;
    free(sorted_indices); sorted_indices = NULL;
    free(node_sire);      node_sire      = NULL;
    free(node_dam);       node_dam       = NULL;
    free(merge_buf);      merge_buf      = NULL;
    merge_cap = 0;
    pop_size = 0;
}

// ==========================================
// 工具函数
// ==========================================
static int cmp_int(const void *a, const void *b) {
    int x = *(const int *)a, y = *(const int *)b;
    return (x > y) - (x < y);   /* 防溢出的安全比较 */
}

/* 在压缩 id 表中二分查找节点下标；找不到返回 -1 */
static int get_idx(int id) {
    if (id <= 0) return -1;
    int *res = (int *)bsearch(&id, all_ids, pop_size, sizeof(int), cmp_int);
    return res ? (int)(res - all_ids) : -1;
}

/* 在按列升序的稀疏行中二分查找指定列的值；不存在返回 0 */
static double row_find(const SparseEntry *row, int n, int col) {
    int lo = 0, hi = n - 1;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        if (row[mid].col_idx == col) return row[mid].value;
        if (row[mid].col_idx <  col) lo = mid + 1; else hi = mid - 1;
    }
    return 0.0;
}

static int pool_init(size_t chunk_size) {
    pool.chunk_size = chunk_size;
    pool.total_used = 0;
    pool.head = (PoolChunk *)malloc(sizeof(PoolChunk));
    if (!pool.head) return -1;
    pool.head->data = (SparseEntry *)malloc(chunk_size * sizeof(SparseEntry));
    if (!pool.head->data) { free(pool.head); pool.head = NULL; return -1; }
    pool.head->capacity = chunk_size;
    pool.head->used = 0;
    pool.head->next = NULL;
    pool.current = pool.head;
    return 0;
}

static SparseEntry *pool_alloc(size_t count) {
    if (count == 0) return NULL;
    if (pool.current->used + count > pool.current->capacity) {
        size_t new_cap = (count > pool.chunk_size) ? count : pool.chunk_size;
        PoolChunk *nc = (PoolChunk *)malloc(sizeof(PoolChunk));
        if (!nc) return NULL;
        nc->data = (SparseEntry *)malloc(new_cap * sizeof(SparseEntry));
        if (!nc->data) { free(nc); return NULL; }
        nc->capacity = new_cap;
        nc->used = 0;
        nc->next = NULL;
        pool.current->next = nc;
        pool.current = nc;   /* 推进当前指针，防止覆盖旧数据 */
    }
    SparseEntry *ptr = pool.current->data + pool.current->used;
    pool.current->used += count;
    pool.total_used += count;
    return ptr;
}

// ==========================================
// 步骤 1：从输入库读取原始记录
// 成功返回记录数（可为 0），失败返回 -1 并填写 errmsg
// ==========================================
static int load_raw_records(const char *path, RawRec **out, char *errmsg, size_t errsz) {
    sqlite3 *db = NULL;
    sqlite3_stmt *stmt = NULL;
    RawRec *recs = NULL;
    int n = 0, cap = 0;
    *out = NULL;

    if (sqlite3_open_v2(path, &db, SQLITE_OPEN_READONLY, NULL) != SQLITE_OK) {
        snprintf(errmsg, errsz, "无法打开输入数据库: %s", db ? sqlite3_errmsg(db) : "未知错误");
        sqlite3_close(db);
        return -1;
    }

    if (sqlite3_prepare_v2(db, "SELECT ID, SireID, DamID FROM Pedigree", -1, &stmt, NULL) != SQLITE_OK) {
        snprintf(errmsg, errsz, "输入库缺少有效的 Pedigree(ID, SireID, DamID) 表: %s", sqlite3_errmsg(db));
        sqlite3_close(db);
        return -1;
    }

    while (sqlite3_step(stmt) == SQLITE_ROW) {
        if (n == cap) {
            cap = cap ? cap * 2 : 1024;
            RawRec *tmp = (RawRec *)realloc(recs, (size_t)cap * sizeof(RawRec));
            if (!tmp) { free(recs); sqlite3_finalize(stmt); sqlite3_close(db);
                        snprintf(errmsg, errsz, "内存不足"); return -1; }
            recs = tmp;
        }
        recs[n].id      = sqlite3_column_int(stmt, 0);
        recs[n].sire_id = sqlite3_column_int(stmt, 1);
        recs[n].dam_id  = sqlite3_column_int(stmt, 2);
        n++;
    }
    sqlite3_finalize(stmt);
    sqlite3_close(db);
    *out = recs;
    return n;
}

// ==========================================
// 步骤 2：坐标压缩 + 解析父母索引
// 缺失在库中的双亲会被补为奠基者节点
// ==========================================
static int build_index(const RawRec *recs, int n) {
    size_t bound = (size_t)n * 3;
    int *ids = (int *)malloc(bound * sizeof(int));
    if (!ids) return -1;

    size_t m = 0;
    for (int i = 0; i < n; i++) {
        ids[m++] = recs[i].id;
        if (recs[i].sire_id > 0) ids[m++] = recs[i].sire_id;
        if (recs[i].dam_id  > 0) ids[m++] = recs[i].dam_id;
    }
    qsort(ids, m, sizeof(int), cmp_int);

    /* 原地去重 */
    size_t uniq = 0;
    for (size_t i = 0; i < m; i++)
        if (uniq == 0 || ids[uniq - 1] != ids[i]) ids[uniq++] = ids[i];

    pop_size = (int)uniq;
    all_ids = ids;   /* 直接复用该缓冲区 */

    node_sire = (int *)malloc((size_t)pop_size * sizeof(int));
    node_dam  = (int *)malloc((size_t)pop_size * sizeof(int));
    if (!node_sire || !node_dam) return -1;
    for (int i = 0; i < pop_size; i++) { node_sire[i] = -1; node_dam[i] = -1; }

    for (int i = 0; i < n; i++) {
        int r = get_idx(recs[i].id);
        node_sire[r] = (recs[i].sire_id > 0) ? get_idx(recs[i].sire_id) : -1;
        node_dam[r]  = (recs[i].dam_id  > 0) ? get_idx(recs[i].dam_id)  : -1;
    }
    return 0;
}

// ==========================================
// 步骤 3：Kahn 拓扑排序（父母必须先于后代）
// 结果写入全局 sorted_indices；成功返回 0
// ==========================================
static int topo_sort(char *errmsg, size_t errsz) {
    int *indeg  = (int *)calloc((size_t)pop_size, sizeof(int));
    int *q      = (int *)malloc((size_t)pop_size * sizeof(int));
    int *cstart = (int *)calloc((size_t)pop_size + 1, sizeof(int));
    int *ccur   = (int *)malloc((size_t)pop_size * sizeof(int));
    int *clist  = NULL;
    int *pos_of = (int *)malloc((size_t)pop_size * sizeof(int));
    int ret = -1;

    if (!indeg || !q || !cstart || !ccur || !pos_of) {
        snprintf(errmsg, errsz, "内存不足");
        goto done;
    }

        /* 统计入度与每个节点的子代数 */
    for (int i = 0; i < pop_size; i++) {
        if (node_sire[i] >= 0) { indeg[i]++; cstart[node_sire[i] + 1]++; }
        if (node_dam[i]  >= 0) { indeg[i]++; cstart[node_dam[i] + 1]++;  }
    }
    for (int i = 0; i < pop_size; i++) cstart[i + 1] += cstart[i];
    clist = (int *)malloc((size_t)(cstart[pop_size] > 0 ? cstart[pop_size] : 1) * sizeof(int));
    if (!clist) { snprintf(errmsg, errsz, "内存不足"); goto done; }
    for (int i = 0; i < pop_size; i++) ccur[i] = cstart[i];
    for (int i = 0; i < pop_size; i++) {
        if (node_sire[i] >= 0) clist[ccur[node_sire[i]]++] = i;
        if (node_dam[i]  >= 0) clist[ccur[node_dam[i]]++]  = i;
    }

    /* Kahn 主循环 */
    {
        int head = 0, tail = 0, processed = 0;
        for (int i = 0; i < pop_size; i++)
            if (indeg[i] == 0) q[tail++] = i;
        sorted_indices = (int *)malloc((size_t)pop_size * sizeof(int));
        if (!sorted_indices) { snprintf(errmsg, errsz, "内存不足"); goto done; }

        while (head < tail) {
            int u = q[head++];
            pos_of[u] = processed;
            sorted_indices[processed++] = u;
            for (int e = cstart[u]; e < cstart[u + 1]; e++) {
                int v = clist[e];
                if (--indeg[v] == 0) q[tail++] = v;
            }
        }
        if (processed != pop_size) {
            snprintf(errmsg, errsz,
                     "系谱存在循环亲子关系（%d/%d 个个体无法排序）",
                     pop_size - processed, pop_size);
            free(sorted_indices); sorted_indices = NULL;
            goto done;
        }
    }

    /* 填充 population（以拓扑序为下标）*/
    population = (CalcAnimal *)calloc((size_t)pop_size, sizeof(CalcAnimal));
    if (!population) { free(sorted_indices); sorted_indices = NULL;
                       snprintf(errmsg, errsz, "内存不足"); goto done; }
    for (int k = 0; k < pop_size; k++) {
        int node = sorted_indices[k];
        population[k].sire_pos   = (node_sire[node] >= 0) ? pos_of[node_sire[node]] : -1;
        population[k].dam_pos    = (node_dam[node]  >= 0) ? pos_of[node_dam[node]]  : -1;
        population[k].row_count  = 0;
        population[k].F          = 0.0;
        population[k].row_entries = NULL;
    }
    ret = 0;

done:
    free(indeg); free(q); free(cstart); free(ccur); free(clist); free(pos_of);
    return ret;
}

/* 取 A 矩阵元素 A(node[x], node[j])。
   下三角存储：元素位于行 max(x,j)、列 min(x,j)；任一端无效时为 0 */
static double getA(int x, int j) {
    if (x < 0 || j < 0) return 0.0;
    int r = (x > j) ? x : j;
    int c = (x > j) ? j : x;
    return row_find(population[r].row_entries, population[r].row_count, c);
}

// ==========================================
// 步骤 4：逐行计算稀疏下三角 A 与 F
//   a_kj = 0.5 * (a_sj + a_dj)   （s/d 为 k 的双亲拓扑位置）
//   a_kk = 1 + 0.5 * a_sd ;  F_k = 0.5 * a_sd
// limit：计算到该拓扑序位置（含）。成功返回 0
// ==========================================
static int compute_rows(int limit) {
    if (pool_init(POOL_CHUNK_ENTRIES) != 0) return -1;

    merge_cap = 1024;
    merge_buf = (SparseEntry *)malloc(merge_cap * sizeof(SparseEntry));
    if (!merge_buf) return -1;

    for (int k = 0; k <= limit; k++) {
        int s = population[k].sire_pos;
        int d = population[k].dam_pos;

        /* 保证暂存缓冲区足够容纳一行 */
        if ((size_t)(k + 1) > merge_cap) {
            size_t ncap = merge_cap * 2;
            while ((size_t)(k + 1) > ncap) ncap *= 2;
            SparseEntry *nb = (SparseEntry *)realloc(merge_buf, ncap * sizeof(SparseEntry));
            if (!nb) return -1;
            merge_buf = nb;
            merge_cap = ncap;
        }

        /* 逐列构造第 k 行的非对角元 */
        size_t cnt = 0;
        for (int j = 0; j < k; j++) {
            double v = 0.5 * (getA(s, j) + getA(d, j));
            if (fabs(v) > EPS_ZERO) {
                merge_buf[cnt].col_idx = j;
                merge_buf[cnt].value   = v;
                cnt++;
            }
        }

        /* 对角元与近交系数：F_k = 0.5 * A(sire, dam) */
        double f_val = 0.5 * getA(s, d);
        double diag  = 1.0 + f_val;

        /* 写入内存池：非对角元 + 对角元（列号最大，天然有序）*/
        SparseEntry *dst = pool_alloc(cnt + 1);
        if (!dst) return -1;
        if (cnt > 0) memcpy(dst, merge_buf, cnt * sizeof(SparseEntry));
        dst[cnt].col_idx = k;
        dst[cnt].value   = diag;

        population[k].row_entries = dst;
        population[k].row_count   = (int)cnt + 1;
        population[k].F           = f_val;

        if ((long long)pool.total_used > MEMORY_LIMIT_ENTRIES) {
            LOGE("Sparse entries exceed memory limit");
            return -1;
        }
    }
    return 0;
}

// ==========================================
// 步骤 5：写出结果库
//   Inbreeding(ID, F) 总是写出；
//   Relationship(ID1, ID2, A) 仅当 pop_size <= MAX_REL_EXPORT_N 时填充
// 成功返回导出的关系对数，出错返回 -1 并填写 errmsg
// ==========================================
static long write_results(const char *out_path, char *errmsg, size_t errsz) {
    sqlite3 *db = NULL;
    sqlite3_stmt *stmt = NULL;
    long exported = 0;

    if (sqlite3_open_v2(out_path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, NULL) != SQLITE_OK) {
        snprintf(errmsg, errsz, "无法创建输出数据库: %s", db ? sqlite3_errmsg(db) : "未知错误");
        if (db) sqlite3_close(db);
        return -1;
    }

    sqlite3_exec(db, "PRAGMA journal_mode=OFF;", NULL, NULL, NULL);
    sqlite3_exec(db, "PRAGMA synchronous=OFF;", NULL, NULL, NULL);
    sqlite3_exec(db,
        "BEGIN;"
        "DROP TABLE IF EXISTS Inbreeding;"
        "CREATE TABLE Inbreeding(ID INTEGER PRIMARY KEY, F REAL NOT NULL);",
        NULL, NULL, NULL);

    if (sqlite3_prepare_v2(db, "INSERT INTO Inbreeding(ID, F) VALUES(?, ?)", -1, &stmt, NULL) != SQLITE_OK) {
        snprintf(errmsg, errsz, "准备插入语句失败: %s", sqlite3_errmsg(db));
        sqlite3_close(db);
        return -1;
    }
    for (int k = 0; k < pop_size; k++) {
        sqlite3_bind_int(stmt, 1, all_ids[sorted_indices[k]]);
        sqlite3_bind_double(stmt, 2, population[k].F);
        if (sqlite3_step(stmt) != SQLITE_DONE) {
            snprintf(errmsg, errsz, "写入近交系数失败: %s", sqlite3_errmsg(db));
            sqlite3_finalize(stmt);
            sqlite3_close(db);
            return -1;
        }
        sqlite3_reset(stmt);
        sqlite3_clear_bindings(stmt);
    }
    sqlite3_finalize(stmt);
    sqlite3_exec(db, "COMMIT;", NULL, NULL, NULL);

    /* 成对关系表：始终保证表结构存在；小种群才填充数据 */
    sqlite3_exec(db,
        "DROP TABLE IF EXISTS Relationship;"
        "CREATE TABLE Relationship(ID1 INTEGER, ID2 INTEGER, A REAL NOT NULL);",
        NULL, NULL, NULL);

    if (pop_size <= MAX_REL_EXPORT_N && exported == 0) {
        sqlite3_exec(db, "BEGIN;", NULL, NULL, NULL);
        if (sqlite3_prepare_v2(db, "INSERT INTO Relationship(ID1, ID2, A) VALUES(?, ?, ?)",
                               -1, &stmt, NULL) == SQLITE_OK) {
            for (int k = 0; k < pop_size; k++) {
                const CalcAnimal *an = &population[k];
                for (int e = 0; e < an->row_count - 1; e++) {  /* 跳过对角元 */
                    double v = an->row_entries[e].value;
                    if (fabs(v) > EPS_REL) {
                        int ci = an->row_entries[e].col_idx;
                        if (ci < 0 || ci >= pop_size) {
                            /* 防御性检查：列索引必须落在有效范围内 */
                            snprintf(errmsg, errsz,
                                     "内部错误：列索引 %d 越界（行=%d, 元素=%d/%d, 种群=%d）",
                                     ci, k, e, an->row_count, pop_size);
                            sqlite3_finalize(stmt);
                            sqlite3_close(db);
                            return -1;
                        }
                        sqlite3_bind_int(stmt, 1, all_ids[sorted_indices[k]]);
                        sqlite3_bind_int(stmt, 2, all_ids[sorted_indices[ci]]);
                        sqlite3_bind_double(stmt, 3, v);
                        if (sqlite3_step(stmt) == SQLITE_DONE) exported++;
                        sqlite3_reset(stmt);
                        sqlite3_clear_bindings(stmt);
                    }
                }
            }
            sqlite3_finalize(stmt);
            stmt = NULL;
        }
        sqlite3_exec(db, "COMMIT;", NULL, NULL, NULL);
    } else {
        exported = -2;   /* 标记：种群过大未导出 */
    }

    sqlite3_close(db);
    return exported;
}

// ==========================================
// JNI 入口一：全量计算
// ==========================================
JNIEXPORT jstring JNICALL
Java_com_example_pedigreeapp_MainActivity_runCalculationNative(JNIEnv *env, jobject thiz,
                                                               jstring input_path, jstring output_path) {
    (void)thiz;
    char msg[512] = "计算失败";
    const char *in_path = NULL;
    const char *out_path = NULL;
    RawRec *recs = NULL;
    int nrec = 0;
    struct timespec ts0, ts1;

    pthread_mutex_lock(&g_calc_mutex);
    clock_gettime(CLOCK_MONOTONIC, &ts0);

    in_path  = (*env)->GetStringUTFChars(env, input_path, NULL);
    out_path = (*env)->GetStringUTFChars(env, output_path, NULL);
    if (!in_path || !out_path) {
        snprintf(msg, sizeof(msg), "获取路径参数失败");
        goto done;
    }

    LOGI("开始计算: %s -> %s", in_path, out_path);

    nrec = load_raw_records(in_path, &recs, msg, sizeof(msg));
    if (nrec < 0) goto done;
    if (nrec == 0) {
        snprintf(msg, sizeof(msg), "输入数据库中没有系谱数据，请先录入或导入");
        goto done;
    }
    LOGI("读取到 %d 条系谱记录", nrec);

    if (build_index(recs, nrec) != 0) {
        snprintf(msg, sizeof(msg), "构建索引阶段内存不足");
        goto done;
    }
    free(recs); recs = NULL;
    LOGI("坐标压缩完成: %d 个个体（含补齐的双亲）", pop_size);

    if (topo_sort(msg, sizeof(msg)) != 0) goto done;
    LOGI("拓扑排序完成");

    if (compute_rows(pop_size - 1) != 0) {
        snprintf(msg, sizeof(msg),
                 "计算阶段内存不足或超出稀疏容量上限（当前 %d 个个体）", pop_size);
        goto done;
    }
    LOGI("稀疏表格法计算完成");

    {
        long exported = write_results(out_path, msg, sizeof(msg));
        if (exported < -1) {
            clock_gettime(CLOCK_MONOTONIC, &ts1);
            double secs = (double)(ts1.tv_sec - ts0.tv_sec)
                        + (double)(ts1.tv_nsec - ts0.tv_nsec) / 1e9;
            snprintf(msg, sizeof(msg),
                     "计算完成：共 %d 个个体，耗时 %.2f 秒（种群较大，未导出成对关系表）",
                     pop_size, secs);
        } else if (exported < 0) {
            goto done;   /* errmsg 已填写 */
        } else {
            clock_gettime(CLOCK_MONOTONIC, &ts1);
            double secs = (double)(ts1.tv_sec - ts0.tv_sec)
                        + (double)(ts1.tv_nsec - ts0.tv_nsec) / 1e9;
            snprintf(msg, sizeof(msg),
                     "计算完成：共 %d 个个体，耗时 %.2f 秒；已导出亲缘关系 %ld 对",
                     pop_size, secs, exported);
        }
        LOGI("%s", msg);
    }

done:
    free(recs);
    cleanup_resources();
    if (in_path)  (*env)->ReleaseStringUTFChars(env, input_path, in_path);
    if (out_path) (*env)->ReleaseStringUTFChars(env, output_path, out_path);
    pthread_mutex_unlock(&g_calc_mutex);
    return (*env)->NewStringUTF(env, msg);
}

// ==========================================
// JNI 入口二：单配对亲缘查询 A(id1, id2)
// 只需计算到两者中较晚的拓扑位置即可取值。
// 返回 NaN 表示个体不存在或数据无效
// ==========================================
JNIEXPORT jdouble JNICALL
Java_com_example_pedigreeapp_MainActivity_queryRelationshipNative(JNIEnv *env, jobject thiz,
                                                                  jstring input_path,
                                                                  jint id1, jint id2) {
    (void)thiz;
    jdouble result = (jdouble)NAN;
    char errmsg[256] = "";
    const char *in_path = NULL;
    RawRec *recs = NULL;
    int nrec = 0;

    pthread_mutex_lock(&g_calc_mutex);

    in_path = (*env)->GetStringUTFChars(env, input_path, NULL);
    if (!in_path) goto done;

    nrec = load_raw_records(in_path, &recs, errmsg, sizeof(errmsg));
    if (nrec <= 0) goto done;

    if (build_index(recs, nrec) != 0) goto done;
    free(recs); recs = NULL;

    if (topo_sort(errmsg, sizeof(errmsg)) != 0) goto done;

    {
        int idx1 = get_idx(id1);
        int idx2 = get_idx(id2);
        if (idx1 < 0 || idx2 < 0) goto done;   /* 个体不在系谱中 */

        int p1 = -1, p2 = -1;
        for (int k = 0; k < pop_size; k++) {
            int node = sorted_indices[k];
            if (node == idx1) p1 = k;
            if (node == idx2) p2 = k;
        }
        int hi = (p1 > p2) ? p1 : p2;
        int lo = (p1 > p2) ? p2 : p1;

        if (compute_rows(hi) != 0) goto done;

        /* 行 hi 中列 lo 的值即对称矩阵元素 A(id1, id2)；对角线时 hi==lo */
        result = (jdouble)row_find(population[hi].row_entries,
                                   population[hi].row_count, lo);
        LOGI("配对查询 A(%d, %d) = %.6f", id1, id2, (double)result);
    }

done:
    free(recs);
    cleanup_resources();
    if (in_path) (*env)->ReleaseStringUTFChars(env, input_path, in_path);
    pthread_mutex_unlock(&g_calc_mutex);
    return result;
}