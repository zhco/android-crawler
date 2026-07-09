package com.zhco.crawler

import android.annotation.SuppressLint
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zhco.crawler.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val dataSources = mutableListOf<DataSource>()
    private var dataCount = 0

    data class DataSource(
        val name: String,
        val headers: List<String>,
        val rows: MutableList<Map<String, String>>
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        with(b.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        }
        b.webView.addJavascriptInterface(WebBridge(), "Crawler")

        b.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                b.progressBar.visibility = android.view.View.GONE
                b.webView.evaluateJavascript(injectJS(), null)
            }
        }
        b.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, progress: Int) {
                b.progressBar.progress = progress
                b.progressBar.visibility = if (progress < 100) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        b.btnGo.setOnClickListener {
            val url = b.etUrl.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            val fullUrl = if (url.startsWith("http")) url else "https://$url"
            b.etUrl.setText(fullUrl)
            dataSources.clear()
            dataCount = 0
            b.btnExport.isEnabled = false
            b.tvFound.text = "加载中..."
            b.tvStatus.text = ""
            b.webView.loadUrl(fullUrl)
        }

        b.btnExport.setOnClickListener { exportAll() }
    }

    private fun updateStatus() {
        runOnUiThread {
            val total = dataSources.sumOf { it.rows.size }
            b.tvFound.text = "找到 ${dataSources.size} 个数据源 / 共 $total 条"
            b.btnExport.isEnabled = total > 0
            b.tvStatus.text = if (dataCount > 0) "API: $dataCount" else ""
        }
    }

    private fun exportAll() {
        if (dataSources.isEmpty()) { toast("无数据"); return }
        dataSources.forEach { ds ->
            val sb = StringBuilder()
            val headers = ds.headers
            sb.appendLine(headers.joinToString(",") { "\"${it}\"" })
            ds.rows.forEach { row ->
                sb.appendLine(headers.joinToString(",") { "\"${row[it] ?: ""}\"" })
            }
            val csv = "\uFEFF$sb"
            saveCSV("${ds.name}_${System.currentTimeMillis()}.csv", csv)
        }
        toast("已导出 ${dataSources.size} 个文件到 Downloads")
    }

    private fun saveCSV(filename: String, csv: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let {
                    contentResolver.openOutputStream(it)?.use { os -> os.write(csv.toByteArray()) }
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                java.io.File(dir, filename).writeText(csv)
            }
        } catch (_: Exception) {}
    }

    private fun injectJS(): String = """
(function(){
  window._crawled = false;
  
  // 钩子: XHR
  (function(){
    var origOpen=XMLHttpRequest.prototype.open;
    var origSend=XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open=function(m,u){
      this._url=u; origOpen.apply(this,arguments);
    };
    XMLHttpRequest.prototype.send=function(){
      var xhr=this;
      xhr.addEventListener('load',function(){
        try{
          var j=JSON.parse(xhr.responseText);
          processJSON(xhr._url, j);
        }catch(e){}
      });
      origSend.apply(this,arguments);
    };
  })();

  // 钩子: fetch
  (function(){
    var origFetch=window.fetch;
    window.fetch=function(){
      return origFetch.apply(this,arguments).then(function(r){
        var url=r.url;
        r.clone().text().then(function(t){
          try{ var j=JSON.parse(t); processJSON(url,j); }catch(e){}
        });
        return r;
      });
    };
  })();

  function processJSON(url, obj){
    if(window._crawled)return;
    var arr=null;
    if(Array.isArray(obj)&&obj.length>0)arr=obj;
    else if(typeof obj==='object'){
      // 找第一个数组字段
      for(var k in obj){
        if(obj.hasOwnProperty(k)&&Array.isArray(obj[k])&&obj[k].length>0&&typeof obj[k][0]==='object'){
          arr=obj[k]; break;
        }
      }
    }
    if(!arr||arr.length===0)return;
    window._crawled=true;
    var keys=Object.keys(arr[0]||{});
    var name='api_'+url.split('?')[0].split('/').pop().substring(0,20);
    var rows=arr.slice(0,500).map(function(item){
      var r={};
      keys.forEach(function(k){ r[k]=String(item[k]??'').substring(0,200); });
      return r;
    });
    window.Crawler.onDataSource(name, keys.join(','), JSON.stringify(rows));
  }

  // 自动提取表格
  setTimeout(function(){
    if(window._crawled)return;
    var tables=document.querySelectorAll('table');
    tables.forEach(function(t,i){
      var rows=[],headers=[],name='table_'+(i+1);
      var caption=t.querySelector('caption');
      if(caption)name=caption.textContent.trim().substring(0,30);
      
      t.querySelectorAll('tr').forEach(function(tr){
        var cells=tr.querySelectorAll('td,th');
        if(cells.length===0)return;
        var isHeader=tr.querySelectorAll('th').length>0&&tr.querySelectorAll('td').length===0;
        if(isHeader&&headers.length===0){
          cells.forEach(function(c){ headers.push(c.textContent.trim()); });
        }else{
          var r={},hasData=false;
          cells.forEach(function(c,i){
            var v=c.textContent.trim();
            if(v&&!/^[-.:#]+$/.test(v))hasData=true;
            var h=headers[i]||'col'+(i+1);
            r[h]=v;
          });
          if(hasData)rows.push(r);
        }
      });

      // 如果没找到 header，用第一行数据长度生成
      if(headers.length===0&&rows.length>0){
        headers=[];
        var first=rows[0];
        var idx=1;
        for(var k in first){ headers.push('col'+idx); idx++; }
      }

      if(rows.length>0)window.Crawler.onDataSource(name, headers.join(','), JSON.stringify(rows));
    });
  },1000);

  // 延迟再扫描一次（等异步渲染的表格）
  setTimeout(function(){
    if(window._crawled)return;
    var tables=document.querySelectorAll('table');
    tables.forEach(function(t,i){
      // 避免重复
      var rows=[],headers=[],name='table_'+(i+1);
      t.querySelectorAll('tr').forEach(function(tr){
        var cells=tr.querySelectorAll('td,th');
        if(cells.length===0)return;
        var isHeader=tr.querySelectorAll('th').length>0&&tr.querySelectorAll('td').length===0;
        if(isHeader&&headers.length===0){
          cells.forEach(function(c){ headers.push(c.textContent.trim()); });
        }else{
          var r={},hasData=false;
          cells.forEach(function(c,i){
            var v=c.textContent.trim();
            if(v&&!/^[-.:#]+$/.test(v))hasData=true;
            r[headers[i]||'col'+(i+1)]=v;
          });
          if(hasData)rows.push(r);
        }
      });
      if(rows.length>0)window.Crawler.onDataSource(name, headers.join(','), JSON.stringify(rows));
    });
  },3000);
})();
""".trimIndent()

    inner class WebBridge {
        @JavascriptInterface
        fun onDataSource(name: String, headersStr: String, rowsJson: String) {
            runOnUiThread {
                try {
                    val headers = headersStr.split(",").filter { it.isNotBlank() }
                    val arr = org.json.JSONArray(rowsJson)
                    val rows = mutableListOf<Map<String, String>>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val row = mutableMapOf<String, String>()
                        obj.keys().forEach { row[it] = obj.getString(it) }
                        rows.add(row)
                    }
                    // 避免重复同名
                    val existing = dataSources.find { it.name == name }
                    if (existing != null) {
                        existing.rows.addAll(rows)
                    } else {
                        dataSources.add(DataSource(name, headers, rows))
                    }
                    toast("$name: ${rows.size} 条")
                    updateStatus()
                } catch (e: Exception) {
                    toast("解析失败: ${e.message}")
                }
            }
        }
    }

    private fun toast(msg: String) {
        runOnUiThread {
            val t = Toast.makeText(this, msg, Toast.LENGTH_SHORT)
            t.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 200)
            t.show()
        }
    }
}
