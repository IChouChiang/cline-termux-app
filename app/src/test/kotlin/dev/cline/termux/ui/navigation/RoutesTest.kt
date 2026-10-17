package dev.cline.termux.ui.navigation

import dev.cline.termux.core.kernel.CheckId
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {
    @Test
    fun `every route path parses back to the same route`() {
        for (route in RouteSamples.all) {
            assertEquals(route.path, route, Routes.parse(route.path))
        }
    }

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `every route survives a kotlinx json round trip`() {
        val json = Json
        for (route in RouteSamples.all) {
            @Suppress("UNCHECKED_CAST")
            val serializer = route::class.serializer() as KSerializer<AppRoute>
            val text = json.encodeToString(serializer, route)
            assertEquals(text, route, json.decodeFromString(serializer, text))
        }
    }

    @Test
    fun `paths follow the docs 07 spelling`() {
        assertEquals("chat/", Chat().path)
        assertEquals("chat/s-1?sheet=approval", Chat("s-1", "approval").path)
        assertEquals("chat/s-1?child=true", ChildChat("s-1").path)
        assertEquals("chat/s-1/changes", ChatChanges("s-1").path)
        assertEquals("chat/s-1/tool/t-9", ChatTool("s-1", "t-9").path)
        assertEquals("kernel/repair?focus=Permission&pushed=true", KernelRepair.of(CheckId.Permission, pushed = true).path)
        assertEquals("schedules?pushed=true", Schedules(pushed = true).path)
        assertEquals("schedules", Schedules().path)
        assertEquals("schedules/edit?template=daily", ScheduleEdit(template = "daily").path)
        assertEquals("settings/providers/open+router", SettingsProvider("open router").path)
        assertEquals("marketplace/entry/skill/a%2Fb", MarketplaceEntryRoute("skill", "a/b").path)
    }

    @Test
    fun `ids are encoded so slashes and query characters cannot split a path`() {
        val path = ChatTool(RouteSamples.AWKWARD_ID, "x").path
        assertEquals(4, path.substringBefore('?').split('/').size)
        assertFalse(path.contains('?'))
    }

    @Test
    fun `literal segments win over arguments`() {
        assertEquals(SettingsProvidersAdd, Routes.parse("settings/providers/add"))
        assertEquals(SettingsProvider("anthropic"), Routes.parse("settings/providers/anthropic"))
        assertEquals(ScheduleEdit(), Routes.parse("schedules/edit"))
        assertEquals(ScheduleDetails("s1"), Routes.parse("schedules/s1"))
        assertEquals(KernelSettings, Routes.parse("kernel"))
        assertEquals(KernelRepair(), Routes.parse("kernel/repair"))
    }

    @Test
    fun `parse tolerates a leading slash and whitespace and rejects unknown paths`() {
        assertEquals(Sessions, Routes.parse(" /sessions "))
        assertEquals(Chat(), Routes.parse("chat"))
        assertNull(Routes.parse("nowhere"))
        assertNull(Routes.parse("chat/a/b/c/d"))
        assertNull(Routes.parse(""))
    }

    @Test
    fun `chat sheet reads only the known wire names`() {
        assertEquals(ChatSheet.Approval, Chat("s", "approval").chatSheet)
        assertEquals(ChatSheet.Question, Chat("s", "question").chatSheet)
        assertNull(Chat("s", "kernel_status").chatSheet)
        assertNull(Chat("s").chatSheet)
    }

    @Test
    fun `kernel repair focus maps to a check id`() {
        assertEquals(CheckId.Permission, KernelRepair.of(CheckId.Permission, pushed = false).checkId)
        assertNull(KernelRepair().checkId)
        assertNull(KernelRepair("Bogus").checkId)
    }

    @Test
    fun `route kinds follow docs 07 section 6 2`() {
        assertEquals(RouteKind.TopLevel, Routes.kindOf(Chat()))
        assertEquals(RouteKind.TopLevel, Routes.kindOf(Chat("s")))
        assertEquals(RouteKind.TopLevel, Routes.kindOf(Schedules()))
        assertEquals(RouteKind.Pushed, Routes.kindOf(Schedules(pushed = true)))
        assertEquals(RouteKind.TopLevel, Routes.kindOf(Customize()))
        assertEquals(RouteKind.Pushed, Routes.kindOf(Customize(pushed = true)))
        assertEquals(RouteKind.TopLevel, Routes.kindOf(Settings("x")))
        assertEquals(RouteKind.Pushed, Routes.kindOf(ChildChat("s")))
        assertEquals(RouteKind.Pushed, Routes.kindOf(SettingsProvider("p")))
        assertEquals(RouteKind.Pushed, Routes.kindOf(Search))
        for (editor in listOf(McpEdit(), ScheduleEdit(), AboutDiagnostics, WorkspaceBrowse(), SettingsProvidersAdd)) {
            assertTrue(Routes.isEditor(editor))
            assertEquals(RouteKind.Editor, Routes.kindOf(editor))
        }
        val editors = RouteSamples.shell.filter(Routes::isEditor).map { it::class }.toSet()
        assertEquals(5, editors.size)
    }

    @Test
    fun `area is the last top-level entry of the back stack`() {
        assertEquals(TopLevelDestination.Chats, Routes.areaOf(emptyList()))
        assertEquals(TopLevelDestination.Chats, Routes.areaOf(listOf(Chat(), ChildChat("c"), ChatChanges("c"))))
        assertEquals(TopLevelDestination.Settings, Routes.areaOf(listOf(Chat(), Settings(), SettingsProviders, SettingsProvider("p"))))
        // Schedules pushed from Settings stays in the Settings area (CR §0.2).
        assertEquals(TopLevelDestination.Settings, Routes.areaOf(listOf(Chat(), Settings(), Schedules(pushed = true))))
        assertEquals(TopLevelDestination.Schedules, Routes.areaOf(listOf(Chat(), Schedules(), ScheduleDetails("s"), ScheduleEdit("s"))))
    }

    @Test
    fun `each top-level destination opens its own route`() {
        for (destination in TopLevelDestination.entries) {
            assertEquals(destination, Routes.topLevelOf(Routes.routeOf(destination)))
        }
    }

    @Test
    fun `with query leaves out null values and encodes the rest`() {
        assertEquals("base", Routes.withQuery("base", "a" to null))
        assertEquals("base?a=1+2&c=%26", Routes.withQuery("base", "a" to "1 2", "b" to null, "c" to "&"))
    }
}
