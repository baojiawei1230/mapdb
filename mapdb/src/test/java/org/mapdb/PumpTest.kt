package org.mapdb

import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PumpTest{

    @Test fun single(){
        check((1..6).map{Pair(it, it*2)})
    }


    @Test fun cent(){
        check((1..100).map{Pair(it, it*2)})
    }

    @Test fun kilo(){
        check((1..1000).map{Pair(it, it*2)})
    }


    @Test fun mega(){
        check((1..1000000).map{Pair(it, it*2)})
    }


    private fun check(source: List<Pair<Int, Int>>) {
        val store = StoreTrivial()
        val taker = Pump.treeMap(
                store = store,
                keySerializer = Serializer.INTEGER,
                valueSerializer = Serializer.INTEGER
        )
        taker.takeAll(source)
        taker.finish()

        val root = taker.rootRecidRecid
                ?: throw AssertionError()
        assertNotEquals(0L, root)

        val map = BTreeMap.make(
                store = store,
                rootRecidRecid = root,
                valueSerializer = Serializer.INTEGER,
                keySerializer = Serializer.INTEGER)
//        map.printStructure(System.out)
        map.verify()

        assertEquals(source.size, map.size)
        source.forEach {
            assertEquals(it.second, map[it.first])
        }

    }
}