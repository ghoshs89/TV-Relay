package dev.relay.tv.companion

import dev.relay.tv.RelayApp
import dev.relay.tv.data.LibraryEdits
import dev.relay.tv.data.ProviderNames
import dev.relay.tv.ui.LiveBrowse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The phone companion's library editor: a page plus the JSON it reads and saves. Runs on the
 * main thread (the server calls it through onMain), like every other change to the store.
 */
internal object LibraryPage {
    private val app get() = RelayApp.instance

    private fun strings(list: Collection<String>) = JsonArray(list.map { JsonPrimitive(it) })

    fun snapshot(): String {
        val store = app.store
        val catalog = app.catalog
        val s = store.settings
        val pid = app.session.profileId
        val renames = store.channelCustom.renames
        val groups = LiveBrowse.group(catalog.liveCats)
        val root = buildJsonObject {
            put("profile", app.session.profile?.name ?: "")
            put("countries", buildJsonArray {
                groups.orEmpty().filter { it.key != "__other" }.forEach { g ->
                    add(buildJsonObject {
                        put("key", "livegroup:${g.key}")
                        put("name", g.label)
                        put("count", g.cats.size)
                    })
                }
            })
            val groupOf = HashMap<String, String>()
            val shortName = HashMap<String, String>()
            groups.orEmpty().forEach { g ->
                g.cats.forEach { c ->
                    if (g.key != "__other") groupOf[c.id] = g.key
                    shortName[c.id] = c.name
                }
            }
            put("live", buildJsonArray {
                catalog.liveCats.forEach { c ->
                    add(buildJsonObject {
                        put("key", "live:${c.id}")
                        put("id", c.id)
                        put("name", ProviderNames.clean(shortName[c.id] ?: c.name))
                        put("group", groupOf[c.id] ?: "")
                        put("count", catalog.channelsByCat[c.id]?.size ?: 0)
                    })
                }
            })
            put("vod", buildJsonArray {
                catalog.vodCats.forEach { c ->
                    add(buildJsonObject {
                        put("key", "vod:${c.id}")
                        put("id", c.id)
                        put("name", ProviderNames.clean(c.name))
                        put("count", catalog.moviesByCat[c.id]?.size ?: 0)
                    })
                }
            })
            put("series", buildJsonArray {
                catalog.seriesCats.forEach { c ->
                    add(buildJsonObject {
                        put("key", "series:${c.id}")
                        put("id", c.id)
                        put("name", ProviderNames.clean(c.name))
                        put("count", catalog.seriesByCat[c.id]?.size ?: 0)
                    })
                }
            })
            put("hidden", strings(s.hiddenCats))
            put("pinnedLive", strings(store.pinnedCats))
            put("pinnedVod", strings(s.pinnedVod))
            put("pinnedSeries", strings(s.pinnedSeries))
            put("favLive", buildJsonArray {
                store.favs(pid).filter { it.kind == "live" }.forEach { f ->
                    val ch = catalog.channelById[f.id]
                    add(buildJsonObject {
                        put("id", f.id)
                        put("name", ProviderNames.clean(ch?.name ?: f.name))
                        put("renamed", f.id in renames)
                    })
                }
            })
            put("renames", JsonObject(renames.entries.associate { (k, v) -> k.toString() to JsonPrimitive(v) }))
        }
        return root.toString()
    }

    /** Saves a change from the page; returns a summary for the phone. */
    fun apply(text: String): String {
        val c = LibraryEdits.parse(text)
        val store = app.store
        val pid = app.session.profileId
        val s = store.settings
        store.settings = s.copy(hiddenCats = c.hidden, pinnedVod = c.pinnedVod, pinnedSeries = c.pinnedSeries)
        store.pinnedCats = c.pinnedLive
        c.favLive?.let { order -> store.setFavs(pid, LibraryEdits.reorderFavs(store.favs(pid), order)) }
        var renamed = 0
        c.renames?.let { changes ->
            val custom = store.channelCustom
            val merged = LibraryEdits.mergeRenames(custom.renames, changes)
            if (merged != custom.renames) {
                renamed = changes.size
                store.channelCustom = custom.copy(renames = merged)
                app.catalog.applyCustom()
            }
        }
        val pinned = c.pinnedLive.size + c.pinnedVod.size + c.pinnedSeries.size
        return "Saved to your TV: ${c.hidden.size} hidden, $pinned pinned" +
            (c.favLive?.let { ", ${it.size} favourite channels" } ?: "") +
            (if (renamed > 0) ", $renamed names changed" else "") + "."
    }

    fun html(token: String, css: String): String = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Relay library</title><style>$css$EXTRA_CSS</style></head><body><main>
        <h1>Relay <span>library editor</span></h1>
        <p class="hint"><a href="/$token/">Back</a> · Changes apply when you press Save. Search still finds hidden categories on the TV.</p>
        <input id="find" placeholder="Filter by name">
        <div class="tabs"><button data-t="live" class="on">Live TV</button><button data-t="vod">Movies</button><button data-t="series">Series</button><button data-t="fav">Favourites</button></div>
        <div id="list"><p class="hint">Loading…</p></div>
        <div class="bar"><button id="save" type="button">Save to TV</button><span id="result" class="hint"></span></div>
        <script>$SCRIPT</script>
        </main></body></html>
    """.trimIndent().replace("__TOKEN__", token)

    private const val EXTRA_CSS = """
        .tabs{display:flex;gap:6px;margin:12px 0}.tabs button{margin:0;padding:8px 14px;background:#1e2b45;color:#e8eef7}
        .tabs button.on{background:#3ddc97;color:#000}
        .row{display:flex;align-items:center;gap:8px;padding:9px 4px;border-bottom:1px solid #1e2b45}
        .row .n{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.row .c{color:#8a98b0;font-size:13px}
        .row.off .n{color:#8a98b0;text-decoration:line-through}.row.head{background:#141e30;font-weight:600}
        .row button{margin:0;padding:6px 10px;font-size:13px;background:#1e2b45;color:#e8eef7}.row button.on{background:#f5c542;color:#000}
        .row input[type=text]{margin:0;padding:6px;font-size:14px}.sub{padding-left:22px}
        .bar{position:sticky;bottom:0;background:#0b1220;padding:10px 0;display:flex;gap:12px;align-items:center}
        .bar button{margin:0}input[type=checkbox]{width:22px;height:22px;flex:none;margin:0}
    """

    private const val SCRIPT = """
var T='/__TOKEN__/', D=null, tab='live', open={};
function el(tag,cls,text){var e=document.createElement(tag);if(cls)e.className=cls;if(text!=null)e.textContent=text;return e;}
function hidden(k){return D.hidden.indexOf(k)>=0;}
function setHidden(k,h){var i=D.hidden.indexOf(k);if(h&&i<0)D.hidden.push(k);if(!h&&i>=0)D.hidden.splice(i,1);}
function pinList(k){return k.indexOf('live:')==0?D.pinnedLive:k.indexOf('vod:')==0?D.pinnedVod:D.pinnedSeries;}
function catRow(c,sub){
  var r=el('div','row'+(sub?' sub':'')+(hidden(c.key)?' off':''));
  var cb=el('input');cb.type='checkbox';cb.checked=!hidden(c.key);cb.onchange=function(){setHidden(c.key,!cb.checked);draw();};
  var p=pinList(c.key),pin=el('button',p.indexOf(c.id)>=0?'on':'',p.indexOf(c.id)>=0?'Pinned':'Pin');
  pin.onclick=function(){var i=p.indexOf(c.id);if(i>=0)p.splice(i,1);else p.push(c.id);draw();};
  r.append(cb,el('span','n',c.name),el('span','c',String(c.count)),pin);return r;
}
function draw(){
  var L=document.getElementById('list'),q=document.getElementById('find').value.toLowerCase();L.textContent='';
  function match(n){return !q||n.toLowerCase().indexOf(q)>=0;}
  if(tab=='fav'){
    if(!D.favLive.length){L.append(el('p','hint','No favourite channels on this profile yet.'));return;}
    D.favLive.forEach(function(f,i){
      if(!match(f.name))return;
      var r=el('div','row'),name=el('input');name.type='text';name.value=(D.renames[f.id]||'');name.placeholder=f.name;
      name.onchange=function(){D.renames[f.id]=name.value.trim();D.renamesTouched=true;};
      var up=el('button','','↑'),dn=el('button','','↓'),rm=el('button','','✕');
      up.onclick=function(){if(i>0){D.favLive.splice(i-1,0,D.favLive.splice(i,1)[0]);draw();}};
      dn.onclick=function(){if(i<D.favLive.length-1){D.favLive.splice(i+1,0,D.favLive.splice(i,1)[0]);draw();}};
      rm.onclick=function(){D.favLive.splice(i,1);draw();};
      var n=el('span','n');n.append(name);r.append(n,up,dn,rm);L.append(r);
    });
    L.append(el('p','hint','Type a new name to rename a channel; clear it to go back to the provider\'s name.'));
    return;
  }
  if(tab=='live'&&D.countries.length){
    D.countries.forEach(function(g){
      var key=g.key.substring(10),cats=D.live.filter(function(c){return c.group==key;});
      var any=match(g.name)||cats.some(function(c){return match(c.name);});if(!any)return;
      var r=el('div','row head'+(hidden(g.key)?' off':''));
      var cb=el('input');cb.type='checkbox';cb.checked=!hidden(g.key);cb.onchange=function(){setHidden(g.key,!cb.checked);draw();};
      var t=el('button','',(open[key]||q)?'▾':'▸');t.onclick=function(){open[key]=!open[key];draw();};
      r.append(cb,el('span','n',g.name),el('span','c',String(g.count)),t);L.append(r);
      if(open[key]||q)cats.forEach(function(c){if(match(c.name)||match(g.name))L.append(catRow(c,true));});
    });
    D.live.filter(function(c){return !c.group&&match(c.name);}).forEach(function(c){L.append(catRow(c,false));});
    return;
  }
  D[tab].filter(function(c){return match(c.name);}).forEach(function(c){L.append(catRow(c,false));});
}
document.querySelectorAll('.tabs button').forEach(function(b){b.onclick=function(){
  document.querySelectorAll('.tabs button').forEach(function(x){x.className='';});b.className='on';tab=b.dataset.t;draw();};});
document.getElementById('find').oninput=draw;
document.getElementById('save').onclick=function(){
  var out=document.getElementById('result');out.textContent='Saving…';
  var body={hidden:D.hidden,pinnedLive:D.pinnedLive,pinnedVod:D.pinnedVod,pinnedSeries:D.pinnedSeries,
    favLive:D.favLive.map(function(f){return f.id;})};
  if(D.renamesTouched)body.renames=D.renames;
  fetch(T+'api/library',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})
    .then(function(x){return x.text();}).then(function(t){out.textContent=t;})
    .catch(function(){out.textContent='The TV did not answer. Is Relay still showing the QR code?';});
};
fetch(T+'api/library').then(function(x){return x.json();}).then(function(d){D=d;draw();})
  .catch(function(){document.getElementById('list').textContent='Could not load your library from the TV.';});
"""
}
