package com.bartschram.franxit.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

// The file keeps the app's original name, so existing products and history stay where they are.
class Db(context: Context) : SQLiteOpenHelper(context, "franxit.db", null, 8) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE product(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                last_checked INTEGER,
                last_error TEXT,
                added INTEGER NOT NULL,
                custom_name TEXT,
                target_price REAL,
                notify INTEGER NOT NULL DEFAULT 1,
                excluded_shops TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE price(
                product_id INTEGER NOT NULL REFERENCES product(id) ON DELETE CASCADE,
                day TEXT NOT NULL,
                price REAL NOT NULL,
                seller TEXT NOT NULL,
                offers INTEGER NOT NULL,
                checked_at INTEGER NOT NULL,
                in_stock INTEGER,
                PRIMARY KEY(product_id, day))"""
        )
        createSitePrice(db)
        createOffer(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createSitePrice(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE product ADD COLUMN custom_name TEXT")
            db.execSQL("ALTER TABLE product ADD COLUMN target_price REAL")
        }
        if (oldVersion < 4) db.execSQL("ALTER TABLE product ADD COLUMN notify INTEGER NOT NULL DEFAULT 1")
        if (oldVersion < 5) db.execSQL("ALTER TABLE price ADD COLUMN in_stock INTEGER")
        if (oldVersion < 6) {
            // excluded_shops belonged to a shop filter that was removed again; the column stays unused.
            db.execSQL("ALTER TABLE product ADD COLUMN excluded_shops TEXT")
            createOffer(db)
        }
        if (oldVersion == 7) {
            // v1.0.71 added shop links, taken out again: remove what they left behind. Products,
            // alerts and price history stay. Dropping a column needs SQLite 3.35 (Android 11+);
            // on older phones the empty column just stays.
            db.execSQL("DROP TABLE IF EXISTS link")
            runCatching { db.execSQL("ALTER TABLE offer DROP COLUMN url") }
            runCatching { db.execSQL("ALTER TABLE product DROP COLUMN url_error") }
        }
    }

    private fun createOffer(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE offer(
                product_id INTEGER NOT NULL REFERENCES product(id) ON DELETE CASCADE,
                seller TEXT NOT NULL,
                price REAL NOT NULL,
                in_stock INTEGER,
                PRIMARY KEY(product_id, seller))"""
        )
    }

    private fun createSitePrice(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE site_price(
                product_id INTEGER NOT NULL REFERENCES product(id) ON DELETE CASCADE,
                day TEXT NOT NULL,
                price REAL NOT NULL,
                PRIMARY KEY(product_id, day))"""
        )
    }

    fun products(): List<Product> {
        val db = readableDatabase
        val history = mutableMapOf<Long, MutableList<PricePoint>>()
        db.rawQuery(
            "SELECT product_id, day, price, seller, offers, checked_at, in_stock FROM price ORDER BY day", null
        ).use { c ->
            while (c.moveToNext()) {
                history.getOrPut(c.getLong(0)) { mutableListOf() } += PricePoint(
                    day = LocalDate.parse(c.getString(1)),
                    price = c.getDouble(2),
                    seller = c.getString(3),
                    offers = c.getInt(4),
                    checkedAt = c.getLong(5),
                    inStock = if (c.isNull(6)) null else c.getInt(6) != 0,
                )
            }
        }
        val siteHistory = mutableMapOf<Long, MutableList<SitePoint>>()
        db.rawQuery("SELECT product_id, day, price FROM site_price ORDER BY day", null).use { c ->
            while (c.moveToNext()) {
                siteHistory.getOrPut(c.getLong(0)) { mutableListOf() } +=
                    SitePoint(LocalDate.parse(c.getString(1)), c.getDouble(2))
            }
        }
        val offers = mutableMapOf<Long, MutableList<ShopOffer>>()
        db.rawQuery("SELECT product_id, seller, price, in_stock FROM offer ORDER BY price", null).use { c ->
            while (c.moveToNext()) {
                offers.getOrPut(c.getLong(0)) { mutableListOf() } +=
                    ShopOffer(c.getString(1), c.getDouble(2), if (c.isNull(3)) null else c.getInt(3) != 0)
            }
        }
        return db.rawQuery(
            "SELECT id, url, name, last_checked, last_error, custom_name, target_price, notify FROM product ORDER BY added", null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    add(
                        Product(
                            id = id,
                            url = c.getString(1),
                            name = c.getString(2),
                            lastChecked = if (c.isNull(3)) null else c.getLong(3),
                            lastError = if (c.isNull(4)) null else c.getString(4),
                            history = history[id].orEmpty(),
                            siteHistory = siteHistory[id].orEmpty(),
                            customName = if (c.isNull(5)) null else c.getString(5),
                            targetPrice = if (c.isNull(6)) null else c.getDouble(6),
                            notify = c.getInt(7) != 0,
                            offers = offers[id].orEmpty(),
                        )
                    )
                }
            }
        }
    }

    /** Returns the id of the product with this url, inserting it if new. */
    fun insertProduct(url: String, name: String): Long {
        val values = ContentValues().apply {
            put("url", url)
            put("name", name)
            put("added", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("product", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return readableDatabase.rawQuery("SELECT id FROM product WHERE url = ?", arrayOf(url)).use {
            it.moveToFirst()
            it.getLong(0)
        }
    }

    /** Stores [point]; with [keepExisting], a price already stored for that day wins. */
    fun savePrice(productId: Long, point: PricePoint, keepExisting: Boolean = false) {
        val values = ContentValues().apply {
            put("product_id", productId)
            put("day", point.day.toString())
            put("price", point.price)
            put("seller", point.seller)
            put("offers", point.offers)
            put("checked_at", point.checkedAt)
            point.inStock?.let { put("in_stock", if (it) 1 else 0) }
        }
        val conflict = if (keepExisting) SQLiteDatabase.CONFLICT_IGNORE else SQLiteDatabase.CONFLICT_REPLACE
        writableDatabase.insertWithOnConflict("price", null, values, conflict)
    }

    fun replaceSiteHistory(productId: Long, points: List<SitePoint>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("site_price", "product_id = ?", arrayOf(productId.toString()))
            for (point in points) {
                db.insertWithOnConflict("site_price", null, ContentValues().apply {
                    put("product_id", productId)
                    put("day", point.day.toString())
                    put("price", point.price)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun markChecked(productId: Long, at: Long, name: String?, error: String?) {
        val values = ContentValues().apply {
            put("last_checked", at)
            put("last_error", error)
            if (!name.isNullOrBlank()) put("name", name)
        }
        writableDatabase.update("product", values, "id = ?", arrayOf(productId.toString()))
    }

    fun setCustomName(productId: Long, name: String?) {
        val values = ContentValues().apply {
            if (name.isNullOrBlank()) putNull("custom_name") else put("custom_name", name.trim())
        }
        writableDatabase.update("product", values, "id = ?", arrayOf(productId.toString()))
    }

    fun setTargetPrice(productId: Long, price: Double?) {
        val values = ContentValues().apply {
            if (price == null) putNull("target_price") else put("target_price", price)
        }
        writableDatabase.update("product", values, "id = ?", arrayOf(productId.toString()))
    }

    fun replaceOffers(productId: Long, offers: List<ShopOffer>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("offer", "product_id = ?", arrayOf(productId.toString()))
            for (o in offers) {
                db.insertWithOnConflict("offer", null, ContentValues().apply {
                    put("product_id", productId)
                    put("seller", o.seller)
                    put("price", o.price)
                    o.inStock?.let { put("in_stock", if (it) 1 else 0) }
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setNotify(productId: Long, notify: Boolean) {
        val values = ContentValues().apply { put("notify", if (notify) 1 else 0) }
        writableDatabase.update("product", values, "id = ?", arrayOf(productId.toString()))
    }

    fun deleteProduct(productId: Long) {
        writableDatabase.delete("product", "id = ?", arrayOf(productId.toString()))
    }
}
