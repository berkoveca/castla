package com.castla.mirror.server

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

class LatestWinsRunnerTest {

    /** Executor that only runs tasks when the test says so. */
    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private fun runner(exec: Executor, ran: MutableList<String>, dropped: MutableList<String>) =
        LatestWinsRunner<String>(exec, run = { ran += it }, superseded = { dropped += it })

    @Test
    fun `single request runs`() {
        val exec = ManualExecutor(); val ran = mutableListOf<String>(); val dropped = mutableListOf<String>()
        val r = runner(exec, ran, dropped)
        r.submit("primary", "waze")
        exec.runAll()
        assertEquals(listOf("waze"), ran)
        assertEquals(emptyList<String>(), dropped)
    }

    @Test
    fun `taps queued behind a slow launch collapse to the newest`() {
        val exec = ManualExecutor(); val ran = mutableListOf<String>(); val dropped = mutableListOf<String>()
        val r = runner(exec, ran, dropped)
        r.submit("primary", "waze")
        r.submit("primary", "youtube")
        r.submit("primary", "spotify")
        exec.runAll()
        assertEquals(listOf("spotify"), ran)
        assertEquals(listOf("waze", "youtube"), dropped)
    }

    @Test
    fun `home after a pending launch wins`() {
        val exec = ManualExecutor(); val ran = mutableListOf<String>(); val dropped = mutableListOf<String>()
        val r = runner(exec, ran, dropped)
        r.submit("primary", "waze")
        r.submit("primary", "HOME")
        exec.runAll()
        assertEquals(listOf("HOME"), ran)
        assertEquals(listOf("waze"), dropped)
    }

    @Test
    fun `different panes are kept and run in order`() {
        val exec = ManualExecutor(); val ran = mutableListOf<String>(); val dropped = mutableListOf<String>()
        val r = runner(exec, ran, dropped)
        r.submit("primary", "waze")
        r.submit("secondary", "spotify")
        exec.runAll()
        assertEquals(listOf("waze", "spotify"), ran)
        assertEquals(emptyList<String>(), dropped)
    }

    @Test
    fun `request arriving while one runs executes after it`() {
        val ran = mutableListOf<String>(); val dropped = mutableListOf<String>()
        val exec = ManualExecutor()
        lateinit var r: LatestWinsRunner<String>
        r = LatestWinsRunner(exec, run = { item ->
            ran += item
            if (item == "waze") { r.submit("primary", "maps"); r.submit("primary", "youtube") }
        }, superseded = { dropped += it })
        r.submit("primary", "waze")
        exec.runAll()
        assertEquals(listOf("waze", "youtube"), ran)
        assertEquals(listOf("maps"), dropped)
    }

    @Test
    fun `a failing request does not stop later ones`() {
        val exec = ManualExecutor(); val ran = mutableListOf<String>()
        val r = LatestWinsRunner<String>(exec, run = { if (it == "bad") error("boom") else ran += it })
        r.submit("primary", "bad")
        exec.runAll()
        r.submit("primary", "waze")
        exec.runAll()
        assertEquals(listOf("waze"), ran)
    }

    @Test
    fun `only one worker is started while busy`() {
        val exec = ManualExecutor()
        val r = LatestWinsRunner<String>(exec, run = {})
        r.submit("primary", "a")
        r.submit("secondary", "b")
        assertEquals(1, exec.tasks.size)
    }
}
