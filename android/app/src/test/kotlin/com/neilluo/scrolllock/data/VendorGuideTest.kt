package com.neilluo.scrolllock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorGuideTest {
    @Test fun knowsTheBigMakersByManufacturerString() {
        assertTrue(VendorGuide.forManufacturer("Xiaomi").maker.startsWith("Xiaomi"))
        assertTrue(VendorGuide.forManufacturer("POCO").maker.startsWith("Xiaomi"))
        assertTrue(VendorGuide.forManufacturer("OPPO").maker.startsWith("Oppo"))
        assertTrue(VendorGuide.forManufacturer("realme").maker.startsWith("Oppo"))
        assertTrue(VendorGuide.forManufacturer("HONOR").maker.startsWith("Honor"))
        assertTrue(VendorGuide.forManufacturer("samsung").maker.startsWith("Samsung"))
    }

    @Test fun unknownMakerStillGetsGenericSteps() {
        val g = VendorGuide.forManufacturer("Fairphone")
        assertEquals("Your phone", g.maker)
        assertTrue(g.steps.isNotEmpty())
        assertTrue(VendorGuide.forManufacturer(null).steps.isNotEmpty())
    }
}
