@file:Suppress("ClickableViewAccessibility")

package com.example.luaoverlay

/*
 * ============================================================================
 *  LUA OVERLAY — app Android (Kotlin) que roda scripts Lua e mostra janelas
 *  flutuantes sobre outros apps.  TUDO em um único arquivo.
 * ============================================================================
 *
 *  ÁRVORE (cada "pacote" abaixo é uma seção deste arquivo; ao crescer, basta
 *  mover cada classe para o seu arquivo/pacote sem mudar nada além de imports):
 *
 *   com.example.luaoverlay
 *   ├── permission/  OverlayPermissionManager
 *   ├── service/     OverlayService, ScriptStore, LogBus
 *   ├── lua/         LuaEngine, LuaSession, LuaSandbox, LuaBridge, LuauCompat
 *   ├── events/      WindowEvents (interface), EventDispatcher
 *   ├── overlay/     WindowManagerHost, FloatingWindow
 *   └── ui/          MainActivity
 *
 *  FLUXO
 *   MainActivity -> OverlayPermissionManager (SYSTEM_ALERT_WINDOW)
 *     -> salva script (ScriptStore) -> startForegroundService(OverlayService)
 *     -> OverlayService cria WindowManagerHost + LuaEngine
 *     -> LuaEngine.run(): nova LuaSession (thread própria) + Globals sandbox
 *     -> script chama criarJanela(...) -> LuaBridge -> main thread -> WindowManagerHost
 *     -> toque/clique na View -> EventDispatcher -> thread Lua -> callback Lua
 *
 *  CONTRATO DA API LUA (só assinaturas; janelas e widgets são ids inteiros)
 *   criarJanela(titulo, x, y, largura, altura) -> janela      -- tudo em dp
 *   texto(janela, str)                         -> widget
 *   botao(janela, rotulo, fn())                -> widget
 *   entrada(janela, dica, fn(texto))           -> widget      -- campo de texto
 *   definirTexto(widget, str)
 *   limpar(janela)                                            -- remove widgets
 *   titulo(janela, str)
 *   mover(janela, x, y)
 *   redimensionar(janela, largura, altura)
 *   focar(janela)                                             -- traz p/ frente
 *   fechar(janela)
 *   onToque(janela, fn(x, y, acao))   acao = "down" | "move" | "up"
 *   apos(ms, fn)        -> timer                              -- uma vez
 *   repetir(ms, fn)     -> timer                              -- periódico
 *   cancelar(timer)
 *   agora()             -> milissegundos (epoch)
 *   log(...)  /  print(...)                                   -- vai p/ o log do app
 *
 *  SANDBOX: só base, table, string, math, bit32 e coroutine. Não existem
 *  io, os, luajava, debug, package, require, dofile, loadfile.
 *
 *  LUAU: o LuaJ é Lua 5.2. Arquivos .luau passam por LuauCompat, que só traduz
 *  atribuições compostas (+= -= *= /= %= ^= ..=). Anotações de tipo, continue,
 *  if-expressions etc. NÃO são suportados e aparecem como erro de sintaxe no log.
 * ============================================================================
 */

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.DragEvent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JseBaseLib
import org.luaj.vm2.lib.jse.JseMathLib
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

// ============================================================================
//  permission/  OverlayPermissionManager
// ============================================================================

object OverlayPermissionManager {
    fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun intent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))

    /** Abre a tela do sistema onde o usuário concede "Exibir sobre outros apps". */
    fun request(activity: Activity) = activity.startActivity(intent(activity))
}

// ============================================================================
//  service/  LogBus, ScriptStore, OverlayService
// ============================================================================

/** Log em memória (últimas 300 linhas) visível na MainActivity. Thread-safe. */
object LogBus {
    private val buf = ArrayDeque<String>()
    private val subs = CopyOnWriteArrayList<(String) -> Unit>()

    fun add(level: String, msg: String) {
        val line = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $level $msg"
        synchronized(this) {
            buf.addLast(line)
            while (buf.size > 300) buf.removeFirst()
        }
        subs.forEach { it(line) }
    }

    fun i(msg: String) = add("·", msg)
    fun e(msg: String) = add("✖", msg)

    @Synchronized
    fun snapshot(): List<String> = buf.toList()

    fun listen(l: (String) -> Unit) { subs.add(l) }
    fun unlisten(l: (String) -> Unit) { subs.remove(l) }
}

/** Guarda o script atual em filesDir; o Service lê daqui (evita extras gigantes no Intent). */
object ScriptStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "script_atual.txt")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lua_overlay", Context.MODE_PRIVATE)

    fun save(ctx: Context, name: String, text: String) {
        file(ctx).writeText(text)
        prefs(ctx).edit().putString("name", name).apply()
    }

    /** @return (nome, código) ou null se nunca houve script salvo. */
    fun load(ctx: Context): Pair<String, String>? {
        val f = file(ctx)
        if (!f.exists()) return null
        return (prefs(ctx).getString("name", "script.lua") ?: "script.lua") to f.readText()
    }
}

class OverlayService : Service() {
    companion object {
        const val ACTION_START = "com.example.luaoverlay.START"
        const val ACTION_RELOAD = "com.example.luaoverlay.RELOAD"
        const val ACTION_STOP = "com.example.luaoverlay.STOP"
        private const val CHANNEL = "overlay_service"
        private const val NOTIF_ID = 1001

        @Volatile
        var running = false
            private set
    }

    private var host: WindowManagerHost? = null
    private var engine: LuaEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        LogBus.i("Serviço criado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Obrigatório chamar rápido após startForegroundService().
        startForegroundCompat()

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!OverlayPermissionManager.canDraw(this)) {
            LogBus.e("Sem permissão de sobreposição; serviço encerrado")
            stopSelf()
            return START_NOT_STICKY
        }

        val h = host ?: WindowManagerHost(this).also { host = it }
        val e = engine ?: LuaEngine(h).also { engine = it }
        val script = ScriptStore.load(this)
        if (script == null) {
            LogBus.e("Nenhum script salvo")
        } else {
            e.run(script.first, script.second)
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Lua Overlay", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val reload = PendingIntent.getForegroundService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_RELOAD), flags
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), flags
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Lua Overlay ativo")
            .setContentText("Janelas e script Lua em execução")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Recarregar", reload).build())
            .addAction(Notification.Action.Builder(null, "Parar", stop).build())
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    override fun onDestroy() {
        super.onDestroy()
        val clean = engine?.stop() ?: true
        host?.clearAll()          // limpa TODAS as janelas
        host = null
        engine = null
        running = false
        LogBus.i("Serviço encerrado")
        // Script em laço infinito que ignorou a interrupção: último recurso.
        if (!clean) Process.killProcess(Process.myPid())
    }
}

// ============================================================================
//  events/  WindowEvents + EventDispatcher
// ============================================================================

/** Eventos emitidos pela UI (sempre na main thread). */
interface WindowEvents {
    fun onTouch(windowId: Int, xDp: Float, yDp: Float, action: String)
    fun onClick(windowId: Int, widgetId: Int)
    fun onInput(windowId: Int, widgetId: Int, text: String)
    fun onClosed(windowId: Int)
}

/** Leva eventos da UI de volta aos callbacks Lua, sempre na thread da VM. */
class EventDispatcher(private val s: LuaSession) : WindowEvents {
    private val touch = ConcurrentHashMap<Int, LuaFunction>()
    private val widgetFns = ConcurrentHashMap<Int, LuaFunction>()
    private val widgetsOfWindow = ConcurrentHashMap<Int, MutableSet<Int>>()
    private val pendingMove = ConcurrentHashMap<Int, AtomicReference<FloatArray?>>()

    fun setTouch(win: Int, fn: LuaFunction) { touch[win] = fn }

    fun setWidgetFn(win: Int, wid: Int, fn: LuaFunction) {
        widgetFns[wid] = fn
        widgetsOfWindow.computeIfAbsent(win) { ConcurrentHashMap.newKeySet<Int>() }.add(wid)
    }

    fun forgetWindow(win: Int) {
        touch.remove(win)
        pendingMove.remove(win)
        widgetsOfWindow.remove(win)?.forEach { widgetFns.remove(it) }
    }

    fun clear() {
        touch.clear(); widgetFns.clear(); widgetsOfWindow.clear(); pendingMove.clear()
    }

    override fun onTouch(windowId: Int, xDp: Float, yDp: Float, action: String) {
        val fn = touch[windowId] ?: return
        if (action == "move") {
            // Coalesce: no máximo um "move" pendente por janela (usa a posição mais recente).
            val ref = pendingMove.computeIfAbsent(windowId) { AtomicReference<FloatArray?>(null) }
            if (ref.getAndSet(floatArrayOf(xDp, yDp)) == null) {
                s.post {
                    val p = ref.getAndSet(null)
                    if (p != null) {
                        fn.call(LuaValue.valueOf(p[0].toDouble()), LuaValue.valueOf(p[1].toDouble()), LuaValue.valueOf("move"))
                    }
                }
            }
        } else {
            s.post {
                fn.call(LuaValue.valueOf(xDp.toDouble()), LuaValue.valueOf(yDp.toDouble()), LuaValue.valueOf(action))
            }
        }
    }

    override fun onClick(windowId: Int, widgetId: Int) {
        val fn = widgetFns[widgetId] ?: return
        s.post { fn.call() }
    }

    override fun onInput(windowId: Int, widgetId: Int, text: String) {
        val fn = widgetFns[widgetId] ?: return
        s.post { fn.call(LuaValue.valueOf(text)) }
    }

    override fun onClosed(windowId: Int) = forgetWindow(windowId)
}

// ============================================================================
//  lua/  LuaEngine, LuaSession, LuaSandbox, LuaBridge, LuauCompat
// ============================================================================

/**
 * Uma execução de script: thread própria, timers e dispatcher. Ao recarregar/parar,
 * a sessão inteira é descartada e uma nova é criada (estado limpo).
 */
class LuaSession(val host: WindowManagerHost) {
    @Volatile
    var alive = true
        private set

    val main = Handler(Looper.getMainLooper())
    val dispatcher = EventDispatcher(this)
    private val ids = AtomicInteger(0)
    private val timers = ConcurrentHashMap<Int, ScheduledFuture<*>>()

    val executor = ScheduledThreadPoolExecutor(
        1,
        ThreadFactory { r -> Thread(null, r, "lua-vm", 4L * 1024 * 1024).apply { isDaemon = true } }
    ).apply { removeOnCancelPolicy = true }

    fun nextId(): Int = ids.incrementAndGet()

    /** Executa na thread Lua, com tratamento de erro (nunca derruba o Service). */
    fun post(block: () -> Unit) {
        if (!alive) return
        try {
            executor.execute { guarded(block) }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun guarded(block: () -> Unit) {
        if (!alive) return
        try {
            block()
        } catch (e: LuaError) {
            report("${e.message}")
        } catch (t: Throwable) {
            report(t.toString())
        }
    }

    /** Erro visível: log + janela vermelha sobre os outros apps. */
    fun report(msg: String) {
        LogBus.e(msg)
        val shown = if (msg.length > 600) msg.take(600) + "…" else msg
        main.post { if (alive) host.showError(shown) }
    }

    /** Toda chamada de UI vinda do Lua passa por aqui: vai para a main thread. */
    fun ui(block: WindowManagerHost.() -> Unit) {
        main.post { if (alive) host.block() }
    }

    fun schedule(ms: Long, repeat: Boolean, fn: LuaFunction): Int {
        val id = nextId()
        val delay = ms.coerceAtLeast(16)
        val task = Runnable {
            if (alive) {
                try {
                    fn.call()
                } catch (t: Throwable) {
                    report(if (t is LuaError) "${t.message}" else t.toString())
                    timers.remove(id)?.cancel(false)
                }
            }
        }
        try {
            timers[id] = if (repeat) executor.scheduleAtFixedRate(task, delay, delay, TimeUnit.MILLISECONDS)
            else executor.schedule(task, delay, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
        }
        return id
    }

    fun cancel(id: Int) { timers.remove(id)?.cancel(false) }

    /** @return true se a thread Lua terminou rápido; false se está travada em laço. */
    fun shutdown(): Boolean {
        alive = false
        timers.values.forEach { it.cancel(false) }
        timers.clear()
        executor.shutdownNow()
        dispatcher.clear()
        return try {
            executor.awaitTermination(300, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
    }
}

/** Cria/recarrega/para a VM Lua. Chamar sempre da main thread. */
class LuaEngine(private val host: WindowManagerHost) {
    private var session: LuaSession? = null

    val isRunning: Boolean get() = session?.alive == true

    fun run(name: String, source: String) {
        stop()
        host.clearAll()
        val s = LuaSession(host)
        session = s
        host.events = s.dispatcher

        val code = if (name.endsWith(".luau", ignoreCase = true)) {
            LogBus.i("Luau: só atribuições compostas são traduzidas (LuaJ = Lua 5.2)")
            LuauCompat.translate(source)
        } else source

        s.post {
            val g = LuaSandbox.newGlobals(s)
            LuaBridge(s).install(g)
            LogBus.i("▶ $name")
            g.load(code, name).call()
            LogBus.i("✔ corpo principal de $name terminou (callbacks e timers seguem ativos)")
        }
    }

    /** @return false se a thread Lua não respondeu à interrupção. */
    fun stop(): Boolean {
        val s = session ?: return true
        session = null
        val clean = s.shutdown()
        if (!clean) LogBus.e("Script não respondeu à parada (laço infinito?)")
        host.events = null
        host.clearAll()
        return clean
    }
}

object LuaSandbox {
    private val BLOCKED = listOf("io", "os", "luajava", "debug", "package", "require", "dofile", "loadfile")

    fun newGlobals(s: LuaSession): Globals {
        val g = Globals()
        g.load(JseBaseLib())
        g.load(PackageLib())
        g.load(Bit32Lib())
        g.load(TableLib())
        g.load(StringLib())
        g.load(CoroutineLib())
        g.load(JseMathLib())
        g.load(DebugLib())          // só para o watchdog abaixo; removido antes do script rodar
        LoadState.install(g)
        LuaC.install(g)

        // Watchdog: a cada 20k instruções checa se a sessão foi parada e aborta o script.
        try {
            val stopFn = object : ZeroArgFunction() {
                override fun call(): LuaValue = LuaValue.valueOf(!s.alive)
            }
            g.load(
                "local stop = ...\n" +
                    "debug.sethook(function() if stop() then error('script interrompido', 0) end end, '', 20000)",
                "watchdog"
            ).call(stopFn)
        } catch (t: Throwable) {
            LogBus.e("Watchdog indisponível: $t")
        }

        for (n in BLOCKED) g.set(n, LuaValue.NIL)
        return g
    }
}

object LuauCompat {
    private val compound = Regex("""^(\s*)([A-Za-z_][\w.\[\]"']*)\s*(\.\.|[-+*/%^])=\s*(.+)$""")

    fun translate(src: String): String = src.lineSequence().joinToString("\n") { line ->
        if (line.trimStart().startsWith("--")) line
        else compound.replace(line) { m ->
            var rhs = m.groupValues[4]
            var comment = ""
            val ci = rhs.indexOf("--")
            if (ci >= 0 && rhs.substring(0, ci).count { it == '"' || it == '\'' } % 2 == 0) {
                comment = " " + rhs.substring(ci)
                rhs = rhs.substring(0, ci).trimEnd()
            }
            "${m.groupValues[1]}${m.groupValues[2]} = ${m.groupValues[2]} ${m.groupValues[3]} ($rhs)$comment"
        }
    }
}

/** Expõe funções Kotlin ao Lua. O Lua só descreve; o Kotlin desenha. */
class LuaBridge(private val s: LuaSession) {

    private fun Globals.reg(name: String, body: (Varargs) -> Varargs) {
        set(name, object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                if (!s.alive) throw LuaError("script parado")
                return body(args)
            }
        })
    }

    private fun joinArgs(a: Varargs): String =
        (1..a.narg()).joinToString("\t") { a.arg(it).tojstring() }

    fun install(g: Globals) {
        g.reg("log") { a -> LogBus.i(joinArgs(a)); LuaValue.NONE }
        g.reg("print") { a -> LogBus.i(joinArgs(a)); LuaValue.NONE }
        g.reg("agora") { _ -> LuaValue.valueOf(System.currentTimeMillis().toDouble()) }

        g.reg("criarJanela") { a ->
            val id = s.nextId()
            val title = a.optjstring(1, "Janela")
            val x = a.optint(2, 40)
            val y = a.optint(3, 120)
            val w = a.optint(4, 240)
            val h = a.optint(5, 200)
            s.ui { create(id, title, x, y, w, h) }
            LuaValue.valueOf(id)
        }
        g.reg("texto") { a ->
            val win = a.checkint(1)
            val text = a.optjstring(2, "")
            val wid = s.nextId()
            s.ui { addText(win, wid, text) }
            LuaValue.valueOf(wid)
        }
        g.reg("botao") { a ->
            val win = a.checkint(1)
            val label = a.optjstring(2, "OK")
            val fn = a.checkfunction(3)
            val wid = s.nextId()
            s.dispatcher.setWidgetFn(win, wid, fn)
            s.ui { addButton(win, wid, label) }
            LuaValue.valueOf(wid)
        }
        g.reg("entrada") { a ->
            val win = a.checkint(1)
            val hint = a.optjstring(2, "")
            val fn = a.checkfunction(3)
            val wid = s.nextId()
            s.dispatcher.setWidgetFn(win, wid, fn)
            s.ui { addInput(win, wid, hint) }
            LuaValue.valueOf(wid)
        }
        g.reg("definirTexto") { a ->
            val wid = a.checkint(1)
            val text = a.optjstring(2, "")
            s.ui { setText(wid, text) }
            LuaValue.NONE
        }
        g.reg("limpar") { a ->
            val win = a.checkint(1)
            s.dispatcher.forgetWindow(win)
            s.ui { clear(win) }
            LuaValue.NONE
        }
        g.reg("titulo") { a ->
            val win = a.checkint(1)
            val t = a.optjstring(2, "")
            s.ui { setTitle(win, t) }
            LuaValue.NONE
        }
        g.reg("mover") { a ->
            val win = a.checkint(1); val x = a.checkint(2); val y = a.checkint(3)
            s.ui { move(win, x, y) }
            LuaValue.NONE
        }
        g.reg("redimensionar") { a ->
            val win = a.checkint(1); val w = a.checkint(2); val h = a.checkint(3)
            s.ui { resize(win, w, h) }
            LuaValue.NONE
        }
        g.reg("focar") { a ->
            val win = a.checkint(1)
            s.ui { bringToFront(win) }
            LuaValue.NONE
        }
        g.reg("fechar") { a ->
            val win = a.checkint(1)
            s.dispatcher.forgetWindow(win)
            s.ui { close(win) }
            LuaValue.NONE
        }
        g.reg("onToque") { a ->
            s.dispatcher.setTouch(a.checkint(1), a.checkfunction(2))
            LuaValue.NONE
        }
        g.reg("apos") { a -> LuaValue.valueOf(s.schedule(a.checklong(1), false, a.checkfunction(2))) }
        g.reg("repetir") { a -> LuaValue.valueOf(s.schedule(a.checklong(1), true, a.checkfunction(2))) }
        g.reg("cancelar") { a -> s.cancel(a.checkint(1)); LuaValue.NONE }
    }
}

// ============================================================================
//  overlay/  WindowManagerHost + FloatingWindow
// ============================================================================

/** Cria/remove/atualiza janelas TYPE_APPLICATION_OVERLAY. Tudo aqui roda na MAIN thread. */
class WindowManagerHost(private val ctx: Context) {
    companion object {
        private const val ERROR_WIN = -1
        private const val ERROR_WIDGET = -2
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val themed: Context = ContextThemeWrapper(ctx, android.R.style.Theme_DeviceDefault)
    private val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    /** id -> janela; a ordem de inserção é o z-order (a última fica na frente). */
    private val windows = LinkedHashMap<Int, FloatingWindow>()
    private val widgetOwner = HashMap<Int, Int>()

    var events: WindowEvents? = null

    fun create(id: Int, title: String, x: Int, y: Int, w: Int, h: Int) {
        if (windows.containsKey(id)) return
        val win = FloatingWindow(themed, id, title, this)
        val lp = WindowManager.LayoutParams(
            ctx.dp(w), ctx.dp(h),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = ctx.dp(x)
            this.y = ctx.dp(y)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        win.lp = lp
        try {
            wm.addView(win, lp)
            windows[id] = win
            setActive(id)
        } catch (e: Exception) {
            LogBus.e("Falha ao criar janela: $e")
        }
    }

    fun addText(win: Int, wid: Int, text: String, color: Int? = null) {
        windows[win]?.addText(wid, text, color)?.also { widgetOwner[wid] = win }
    }

    fun addButton(win: Int, wid: Int, label: String) {
        windows[win]?.addButton(wid, label)?.also { widgetOwner[wid] = win }
    }

    fun addInput(win: Int, wid: Int, hint: String) {
        windows[win]?.addInput(wid, hint)?.also { widgetOwner[wid] = win }
    }

    fun setText(wid: Int, text: String) {
        windows[widgetOwner[wid] ?: return]?.setWidgetText(wid, text)
    }

    fun clear(win: Int) {
        windows[win]?.clearWidgets()
        widgetOwner.values.removeAll { it == win }
    }

    fun setTitle(win: Int, t: String) { windows[win]?.setTitle(t) }

    fun move(win: Int, x: Int, y: Int) {
        val w = windows[win] ?: return
        w.lp.x = ctx.dp(x); w.lp.y = ctx.dp(y)
        update(w)
    }

    fun resize(win: Int, wDp: Int, hDp: Int) {
        val w = windows[win] ?: return
        w.lp.width = ctx.dp(wDp); w.lp.height = ctx.dp(hDp)
        update(w)
    }

    fun update(w: FloatingWindow) {
        try { wm.updateViewLayout(w, w.lp) } catch (_: Exception) { }
    }

    fun setActive(id: Int) {
        windows.values.forEach { it.setActive(it.id == id) }
    }

    /** Z-order: o WindowManager ordena por inserção, então remove e re-adiciona. */
    fun bringToFront(id: Int) {
        val w = windows[id] ?: return
        setActive(id)
        if (windows.keys.last() == id) return
        try {
            wm.removeViewImmediate(w)
            wm.addView(w, w.lp)
            windows.remove(id)
            windows[id] = w
        } catch (e: Exception) {
            LogBus.e("bringToFront falhou: $e")
        }
    }

    /** Janelas com campo de texto só ganham foco (teclado) quando o usuário toca no campo. */
    fun setFocusable(id: Int, focusable: Boolean) {
        val w = windows[id] ?: return
        val f = w.lp.flags
        w.lp.flags = if (focusable) f and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else f or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!focusable) {
            imm.hideSoftInputFromWindow(w.windowToken, 0)
            w.clearFocus()
        }
        update(w)
    }

    fun close(id: Int) {
        val w = windows.remove(id) ?: return
        try { wm.removeViewImmediate(w) } catch (_: Exception) { }
        widgetOwner.values.removeAll { it == id }
        events?.onClosed(id)
        windows.keys.lastOrNull()?.let { setActive(it) }
    }

    fun clearAll() {
        windows.keys.toList().forEach { close(it) }
    }

    fun showError(msg: String) {
        close(ERROR_WIN)
        create(ERROR_WIN, "⚠ Erro Lua", 12, 72, 300, 180)
        addText(ERROR_WIN, ERROR_WIDGET, msg, 0xFFFF8A8A.toInt())
    }
}

/** Janela: barra de título (arrastar), conteúdo rolável, alça de redimensionar, foco e z-order. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class FloatingWindow(
    ctx: Context,
    val id: Int,
    title: String,
    private val host: WindowManagerHost,
) : LinearLayout(ctx) {

    lateinit var lp: WindowManager.LayoutParams

    private val widgets = HashMap<Int, View>()
    private val bar = LinearLayout(ctx)
    private val titleView = TextView(ctx)
    private val body = LinearLayout(ctx)
    private val handle = TextView(ctx)
    private val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    /** ScrollView que também reporta toques (sem consumi-los) para onToque do Lua. */
    private val scroll: ScrollView = object : ScrollView(ctx) {
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            val a = when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> "down"
                MotionEvent.ACTION_MOVE -> "move"
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> "up"
                else -> null
            }
            if (a != null) {
                val d = resources.displayMetrics.density
                host.events?.onTouch(id, e.x / d, e.y / d, a)
            }
            return super.dispatchTouchEvent(e)
        }
    }

    init {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(0xF01B1B2B.toInt())
            cornerRadius = ctx.dp(10).toFloat()
            setStroke(ctx.dp(1), 0xFF45475A.toInt())
        }
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND

        // Barra de título
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(ctx.dp(10), ctx.dp(2), ctx.dp(2), ctx.dp(2))
        titleView.apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val close = TextView(ctx).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
            setOnClickListener { host.close(id) }
        }
        bar.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(close, LinearLayout.LayoutParams(WRAP, WRAP))

        // Conteúdo
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        scroll.addView(body)

        // Alça de redimensionar
        handle.apply {
            text = "◢"
            gravity = Gravity.END
            textSize = 14f
            setTextColor(0xFF9399B2.toInt())
            setPadding(ctx.dp(8), 0, ctx.dp(6), ctx.dp(2))
        }

        addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(handle, LinearLayout.LayoutParams(MATCH, WRAP))

        installDrag()
        installResize()
        setActive(false)
    }

    private fun installDrag() {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        bar.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    val sw = resources.displayMetrics.widthPixels
                    lp.x = (ox + (e.rawX - sx)).toInt().coerceIn(-lp.width + context.dp(56), sw - context.dp(56))
                    lp.y = (oy + (e.rawY - sy)).toInt().coerceAtLeast(0)
                    host.update(this)
                }
            }
            true
        }
    }

    private fun installResize() {
        var sx = 0f; var sy = 0f; var ow = 0; var oh = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ow = lp.width; oh = lp.height }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (ow + (e.rawX - sx)).toInt().coerceAtLeast(context.dp(160))
                    lp.height = (oh + (e.rawY - sy)).toInt().coerceAtLeast(context.dp(96))
                    host.update(this)
                }
            }
            true
        }
    }

    // Foco/z-order: destaca ao tocar; só reordena ao soltar (reordenar remove a View do
    // WindowManager e cancelaria o gesto em andamento).
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) host.setActive(id)
        val r = super.dispatchTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP) post { host.bringToFront(id) }
        return r
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) {
            host.setFocusable(id, false)   // BACK só fecha o teclado/foco
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    fun setActive(active: Boolean) {
        bar.setBackgroundColor(if (active) 0xFF3B5BDB.toInt() else 0xFF313244.toInt())
    }

    fun setTitle(t: String) { titleView.text = t }

    private fun itemParams() = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = context.dp(6) }

    fun addText(wid: Int, text: String, color: Int?): Boolean {
        val tv = TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(color ?: 0xFFE6E6F0.toInt())
        }
        widgets[wid] = tv
        body.addView(tv, itemParams())
        return true
    }

    fun addButton(wid: Int, label: String): Boolean {
        val b = Button(context).apply {
            text = label
            isAllCaps = false
            setOnClickListener { host.events?.onClick(id, wid) }
        }
        widgets[wid] = b
        body.addView(b, itemParams())
        return true
    }

    fun addInput(wid: Int, hint: String): Boolean {
        val et = EditText(context).apply {
            this.hint = hint
            setHintTextColor(0xFF7F849C.toInt())
            setTextColor(0xFFE6E6F0.toInt())
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    host.setFocusable(id, true)
                    v.postDelayed({
                        v.requestFocus()
                        imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                    }, 120)
                }
                false
            }
            setOnEditorActionListener { v, actionId, ev ->
                val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER
                if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                    host.events?.onInput(id, wid, v.text.toString())
                    host.setFocusable(id, false)
                    true
                } else false
            }
        }
        widgets[wid] = et
        body.addView(et, itemParams())
        return true
    }

    fun setWidgetText(wid: Int, text: String) { (widgets[wid] as? TextView)?.text = text }

    fun clearWidgets() { body.removeAllViews(); widgets.clear() }
}

// ============================================================================
//  ui/  MainActivity
// ============================================================================

class MainActivity : ComponentActivity() {

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var permButton: Button
    private lateinit var fileLabel: TextView
    private lateinit var luauBox: CheckBox
    private lateinit var editor: EditText
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private var currentName = "script.lua"
    private val logListener: (String) -> Unit = { line -> runOnUiThread { appendLog(line) } }

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri(uri)
    }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        launchService(OverlayService.ACTION_START)   // com ou sem notificação visível, o serviço roda
    }

    private val dropListener = View.OnDragListener { v, e ->
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_ENTERED -> { root.setBackgroundColor(0x33448AFF); true }
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                root.setBackgroundColor(Color.TRANSPARENT); true
            }
            DragEvent.ACTION_DROP -> { root.setBackgroundColor(Color.TRANSPARENT); handleDrop(v, e) }
            else -> true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val saved = ScriptStore.load(this)
        if (saved != null) {
            setScript(saved.first, saved.second)
        } else {
            setScript("exemplo.lua", EXAMPLE_SCRIPT)
        }
        LogBus.snapshot().forEach { appendLog(it) }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        LogBus.listen(logListener)
    }

    override fun onStop() {
        LogBus.unlisten(logListener)
        persist()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val ok = OverlayPermissionManager.canDraw(this)
        status.text = if (ok) "✔ Permissão de sobreposição concedida"
        else "✖ Falta a permissão \"Exibir sobre outros apps\""
        permButton.isEnabled = !ok
    }

    // ---- UI ---------------------------------------------------------------

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            fitsSystemWindows = true
        }
        status = TextView(this).apply { textSize = 14f }
        permButton = btn("Conceder permissão de sobreposição") { OverlayPermissionManager.request(this) }
        fileLabel = TextView(this).apply { textSize = 12f; setTextColor(0xFF9CA3AF.toInt()) }
        luauBox = CheckBox(this).apply { text = "Tratar como Luau (+=, -=, ..=)"; textSize = 12f }
        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTILINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setBackgroundColor(0xFF111418.toInt())
            setTextColor(0xFFE6E6F0.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setHorizontallyScrolling(false)
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(0xFFA6E3A1.toInt())
        }
        logScroll = ScrollView(this).apply { addView(logView) }

        root.addView(status)
        root.addView(permButton, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(row(
            btn("Abrir .lua/.luau") { openDoc.launch(arrayOf("*/*")) },
            btn("Colar do clipboard") { pasteClipboard() },
        ))
        root.addView(fileLabel)
        root.addView(luauBox)
        root.addView(editor, LinearLayout.LayoutParams(MATCH, 0, 1f))
        root.addView(row(
            btn("▶ Executar / Recarregar") { start() },
            btn("■ Parar") { stopService(Intent(this, OverlayService::class.java)) },
        ))
        root.addView(logScroll, LinearLayout.LayoutParams(MATCH, dp(130)))
        setContentView(root)

        // Arrastar arquivos .lua/.luau (ou texto) de outro app (tela dividida) para cá.
        root.setOnDragListener(dropListener)
        editor.setOnDragListener(dropListener)
    }

    private fun appendLog(line: String) {
        val t = logView.text.toString() + line + "\n"
        logView.text = if (t.length > 8000) t.takeLast(8000) else t
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- Script (abrir / soltar / compartilhar) -----------------------------

    private fun effectiveName(): String {
        val base = currentName.substringBeforeLast('.', currentName)
        return base + if (luauBox.isChecked) ".luau" else ".lua"
    }

    private fun persist() = ScriptStore.save(this, effectiveName(), editor.text.toString())

    private fun setScript(name: String, text: String) {
        currentName = name
        fileLabel.text = name
        luauBox.isChecked = name.endsWith(".luau", ignoreCase = true)
        editor.setText(text)
    }

    /** Carrega, salva e — se o serviço estiver ativo — injeta o script ao vivo (recarrega). */
    private fun loadScript(name: String, text: String) {
        setScript(name, text)
        persist()
        LogBus.i("Script carregado: $name (${text.length} caracteres)")
        if (OverlayService.running) {
            LogBus.i("Serviço ativo: recarregando com o novo script")
            launchService(OverlayService.ACTION_RELOAD)
        }
    }

    private fun importUri(uri: Uri) {
        try {
            val name = queryName(uri) ?: uri.lastPathSegment ?: "script.lua"
            val lower = name.lowercase()
            if (!(lower.endsWith(".lua") || lower.endsWith(".luau") || lower.endsWith(".txt"))) {
                toast("Só arquivos .lua, .luau ou .txt")
                return
            }
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: run { toast("Não consegui ler o arquivo"); return }
            if (bytes.size > 2_000_000) { toast("Arquivo grande demais (máx. 2 MB)"); return }
            loadScript(name, String(bytes, Charsets.UTF_8).removePrefix("\uFEFF"))
        } catch (e: Exception) {
            LogBus.e("Falha ao importar: $e")
            toast("Falha ao importar o arquivo")
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun handleDrop(v: View, e: DragEvent): Boolean {
        val clip = e.clipData ?: return false
        val perms = requestDragAndDropPermissions(e)
        try {
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i)
                val uri = item.uri
                if (uri != null) { importUri(uri); return true }
                val t = item.text
                if (!t.isNullOrEmpty()) {
                    if (v === editor) {
                        editor.text.insert(editor.selectionStart.coerceAtLeast(0), t)
                    } else {
                        loadScript("colado.lua", t.toString())
                    }
                    return true
                }
            }
        } finally {
            perms?.release()
        }
        return false
    }

    private fun pasteClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (t.isNullOrBlank()) toast("Clipboard vazio") else loadScript("colado.lua", t)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        when (i.action) {
            Intent.ACTION_VIEW -> i.data?.let { importUri(it) }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (u != null) importUri(u)
                else i.getStringExtra(Intent.EXTRA_TEXT)?.let { loadScript("compartilhado.lua", it) }
            }
        }
    }

    // ---- Serviço -------------------------------------------------------------

    private fun start() {
        if (!OverlayPermissionManager.canDraw(this)) {
            toast("Conceda a permissão de sobreposição primeiro")
            OverlayPermissionManager.request(this)
            return
        }
        persist()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchService(OverlayService.ACTION_START)
        }
    }

    private fun launchService(action: String) {
        ContextCompat.startForegroundService(
            this, Intent(this, OverlayService::class.java).setAction(action)
        )
    }

    companion object {
        const val EXAMPLE_SCRIPT = """-- Exemplo: contador, relógio e área de toque
local jan = criarJanela("Contador", 40, 120, 240, 220)
local lbl = texto(jan, "Cliques: 0")
local rel = texto(jan, "tempo: 0s")
local n = 0

botao(jan, "+1", function()
  n = n + 1
  definirTexto(lbl, "Cliques: " .. n)
end)
botao(jan, "Zerar", function()
  n = 0
  definirTexto(lbl, "Cliques: 0")
end)

local nome = entrada(jan, "digite e toque em OK", function(t)
  log("digitado: " .. t)
  titulo(jan, "Contador - " .. t)
end)

local seg = 0
repetir(1000, function()
  seg = seg + 1
  definirTexto(rel, "tempo: " .. seg .. "s")
end)

local jan2 = criarJanela("Toque aqui", 40, 380, 240, 160)
local info = texto(jan2, "x=? y=?")
onToque(jan2, function(x, y, acao)
  definirTexto(info, string.format("%s  x=%.0f y=%.0f", acao, x, y))
end)

log("script iniciado")
"""
    }
}
