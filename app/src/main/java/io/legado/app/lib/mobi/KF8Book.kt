package io.legado.app.lib.mobi

import io.legado.app.lib.mobi.entities.FdstHeader
import io.legado.app.lib.mobi.entities.Fragment
import io.legado.app.lib.mobi.entities.KF8Pos
import io.legado.app.lib.mobi.entities.KF8Resource
import io.legado.app.lib.mobi.entities.KF8Section
import io.legado.app.lib.mobi.entities.MobiEntryHeaders
import io.legado.app.lib.mobi.entities.NCX
import io.legado.app.lib.mobi.entities.Skeleton
import io.legado.app.lib.mobi.entities.TOC
import io.legado.app.lib.mobi.utils.readString
import io.legado.app.lib.mobi.utils.readUInt32
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Kindle Format 8 Book
 */
@Suppress("SpellCheckingInspection")
class KF8Book(
    pdbFile: PDBFile,
    headers: MobiEntryHeaders,
    kf8BoundaryOffset: Int,
    resourceStart: Int
) : MobiBook(pdbFile, headers, kf8BoundaryOffset, resourceStart) {

    private var fdstTableStarts: IntArray? = null
    private var fdstTableEnds: IntArray? = null
    private lateinit var skelTable: List<Skeleton>
    private lateinit var fragTable: List<Fragment>
    private var kf8 = headers.kf8!!
    lateinit var sections: List<KF8Section>
    lateinit var sectionIdMap: LinkedHashMap<Int, ArrayList<TOC>>

    init {
        readFdstTable()
        readSkelTable()
        readFragTable()
        processSections()
        processNCX()
        processSectionsMap()
    }

    /**
     * 建立 section id -> toc 的 map
     */
    private fun processSectionsMap() {
        sectionIdMap = linkedMapOf()
        if (toc == null) {
            return
        }
        fun fmap(item: TOC) {
            val index = getIndexByHref(item.href)
            if (index == -1) return
            val array = sectionIdMap.getOrPut(index) { arrayListOf() }
            array.add(item)
            item.subitems?.forEach(::fmap)
        }
        toc!!.forEach(::fmap)
    }

    fun parsePosURI(href: String): KF8Pos? {
        val match = kindlePosRegex.find(href) ?: return null
        val fid = match.groupValues[1].toInt(32)
        val off = match.groupValues[2].toInt(32)
        return KF8Pos(fid, off)
    }

    private fun parseResourceURI(href: String): KF8Resource? {
        val match = kindleResourceRegex.find(href) ?: return null
        val resourceType = match.groupValues[1]
        val id = match.groupValues[2].toInt(32)
        val type = match.groupValues[3]
        return KF8Resource(resourceType, id, type)
    }

    fun getResourceByHref(href: String): ByteArray? {
        val resource = parseResourceURI(href) ?: return null
        if (resource.resourceType == "flow") return null
        return getResource(resource.id - 1).array()
    }

    fun getSectionByHref(href: String): KF8Section? {
        val index = getIndexByHref(href)
        return sections.getOrNull(index)
    }

    private fun getIndexByHref(href: String): Int {
        val pos = parsePosURI(href) ?: return -1
        return getIndexByFID(pos.fid)
    }

    private fun getIndexByFID(fid: Int): Int {
        return sections.indexOfFirst {
            it.frags.any { frag ->
                frag.index == fid
            }
        }
    }

    fun getTextByHref(href: String, nextHref: String, anchor: KF8Pos? = null, marker: String = ""): String {
        val pos = parsePosURI(href) ?: return ""
        val nextPos = parsePosURI(nextHref)
        val index = getIndexByFID(pos.fid)
        val nextIndex = nextPos?.let { getIndexByFID(it.fid) } ?: -1
        val startFid = pos.fid
        val endFid = if (index == nextIndex) nextPos!!.fid else Int.MAX_VALUE
        val section = sections[index]
        val skel = section.skeleton
        val droppedFrags = section.frags.filter { it.index < startFid }
        var droppedFragsLength = droppedFrags.sumOf { it.length }
        val frags = section.frags.filter { it.index in startFid..endFid }
        val length = skel.length + frags.sumOf { it.length }
        val raw = getRaw(skel.offset, section.length)
        val lastFragDroppedLength =
            if (index == nextIndex) frags.last().length - nextPos!!.offset else 0
        val skeleton = ByteArray(length - pos.offset - lastFragDroppedLength)
        var leftBytes = skeleton.size
        raw.copyInto(skeleton, 0, 0, skel.length)
        leftBytes -= skel.length
        var anchorByte: Int? = null
        for ((i, frag) in frags.withIndex()) {
            val isFirstFrag = i == 0
            val isLastFrag = i == frags.lastIndex
            val insertOffset = frag.insertOffset - skel.offset - droppedFragsLength
            val from = if (isFirstFrag) pos.offset else 0
            val to = frag.length - if (isLastFrag) lastFragDroppedLength else 0
            val added = to - from
            anchorByte = anchorByte?.let { if (it >= insertOffset) it + added else it }
            if (anchor?.fid == frag.index && anchor.offset in from..to) anchorByte = insertOffset + anchor.offset - from
            val offset = skel.length + frag.offset
            skeleton.copyInto(
                skeleton,
                insertOffset + frag.length -
                        (if (isFirstFrag) pos.offset else 0) -
                        (if (isLastFrag) lastFragDroppedLength else 0),
                insertOffset,
                skeleton.size - leftBytes
            )
            raw.copyInto(
                skeleton,
                insertOffset,
                offset + if (isFirstFrag) pos.offset else 0,
                offset + frag.length - if (isLastFrag) lastFragDroppedLength else 0
            )
            leftBytes -= frag.length -
                    (if (isFirstFrag) pos.offset else 0) -
                    (if (isLastFrag && index == nextIndex) nextPos!!.offset else 0)
            if (isFirstFrag) {
                droppedFragsLength += pos.offset
            }
        }
        return withAnchor(skeleton, anchorByte, marker)
    }

    fun getSectionText(section: KF8Section, anchor: KF8Pos? = null, marker: String = ""): String {
        val skel = section.skeleton
        val frags = section.frags
        val length = section.length
        val raw = getRaw(skel.offset, length)
        val skeleton = ByteArray(raw.size)
        var leftBytes = raw.size
        raw.copyInto(skeleton, 0, 0, skel.length)
        leftBytes -= skel.length
        var anchorByte: Int? = null
        for (frag in frags) {
            val insertOffset = frag.insertOffset - skel.offset
            anchorByte = anchorByte?.let { if (it >= insertOffset) it + frag.length else it }
            if (anchor?.fid == frag.index && anchor.offset in 0..frag.length) anchorByte = insertOffset + anchor.offset
            val offset = skel.length + frag.offset
            skeleton.copyInto(
                skeleton,
                insertOffset + frag.length,
                insertOffset,
                skeleton.size - leftBytes
            )
            raw.copyInto(skeleton, insertOffset, offset, offset + frag.length)
            leftBytes -= frag.length
        }
        return withAnchor(skeleton, anchorByte, marker)
    }

    private fun withAnchor(bytes: ByteArray, position: Int?, marker: String): String {
        val html = String(bytes, charset)
        if (position == null || marker.isEmpty()) return html
        var at = String(bytes, 0, position.coerceIn(0, bytes.size), charset).length
        val opening = html.lastIndexOf('<', at.coerceAtMost(html.lastIndex))
        if (opening > html.lastIndexOf('>', at.coerceAtMost(html.lastIndex))) at = opening
        return html.substring(0, at) + marker + html.substring(at)
    }

    private fun getRaw(offset: Int, len: Int): ByteArray {
        val inputStream = getTextRecordInputStream()
        val byteArray = ByteArray(len)
        inputStream.skip(offset.toLong())
        inputStream.read(byteArray)
        return byteArray
    }

    private fun processNCX() {
        val ncx = getNCX() ?: return
        fun fmap(item: NCX): TOC {
            val (fid, off) = item.pos!!
            val href = makePosURI(fid, off)
            return TOC(item.label, href, item.children?.map(::fmap))
        }
        toc = ncx.map(::fmap)
    }

    private fun makePosURI(fid: Int, off: Int): String {
        val encodedFid = fid.toString(32).uppercase(Locale.ROOT).padStart(4, '0')
        val encodedOff = off.toString(32).uppercase(Locale.ROOT).padStart(10, '0')
        return "kindle:pos:fid:$encodedFid:off:$encodedOff"
    }

    private fun processSections() {
        sections = skelTable.fold(arrayListOf()) { arr, skel ->
            val last = arr.lastOrNull()
            val index = arr.size
            val fragStart = last?.fragEnd ?: 0
            val fragEnd = fragStart + skel.numFrag
            val frags = fragTable.slice(fragStart..<fragEnd)
            val length = skel.length + frags.sumOf { it.length }
            val totalLength = (last?.totalLength ?: 0) + length
            val href = frags.firstOrNull()?.let { makePosURI(it.index, 0) } ?: ""
            val section = KF8Section(index, skel, frags, fragEnd, length, totalLength, href)
            last?.next = section
            arr.add(section)
            arr
        }
    }

    private fun readFragTable() {
        val fragData = getIndexData(kf8.frag)
        fragTable = fragData.table.map { indexEntry ->
            val tagMap = indexEntry.tagMap
            Fragment(
                indexEntry.label.toInt(),
                fragData.cncx[tagMap[2].tagValues[0]],
                tagMap[4].tagValues[0],
                tagMap[6].tagValues[0],
                tagMap[6].tagValues[1]
            )
        }
    }

    private fun readSkelTable() {
        skelTable = getIndexData(kf8.skel).table.mapIndexed { index, indexEntry ->
            val tagMap = indexEntry.tagMap
            Skeleton(
                index,
                indexEntry.label,
                tagMap[1].tagValues[0],
                tagMap[6].tagValues[0],
                tagMap[6].tagValues[1],
            )
        }
    }

    private fun readFdstTable() {
        try {
            val fdstBuffer = getRecord(kf8.fdst)
            val fdstHeader = readFdstHeader(fdstBuffer)
            fdstTableStarts = IntArray(fdstHeader.numEntries)
            fdstTableEnds = IntArray(fdstHeader.numEntries)
            fdstBuffer.position(12)
            for (i in 0..<fdstHeader.numEntries) {
                fdstTableStarts!![i] = fdstBuffer.readUInt32()
                fdstTableEnds!![i] = fdstBuffer.readUInt32()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun readFdstHeader(buffer: ByteBuffer): FdstHeader {
        val magic = buffer.readString(0, 4)
        if (magic != "FDST") error("Missing FDST record")
        val numEntries = buffer.readUInt32(8)
        return FdstHeader(magic, numEntries)
    }

    companion object {
        val kindlePosRegex = "kindle:pos:fid:(\\w+):off:(\\w+)".toRegex()
        val kindleResourceRegex =
            "kindle:(flow|embed):(\\w+)(?:\\?mime=(\\w+/[-+.\\w]+))?".toRegex()
    }

}
