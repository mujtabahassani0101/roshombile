package com.haseebullahjan.roshd.ui.components

import com.haseebullahjan.roshd.platform.WeakHostRegistry
import org.junit.Assert.*
import org.junit.Test

class WeakHostRegistryTest {
    private class Host(var destroyed:Boolean=false)
    @Test fun closingTheCameraRestoresTheUnderlyingNativeHost() {
        val main=Host();val camera=Host();val hosts=WeakHostRegistry<Host> { !it.destroyed }
        hosts.attach(main);hosts.attach(camera);assertSame(camera,hosts.current())
        hosts.detach(camera);assertSame(main,hosts.current())
    }
    @Test fun aDestroyedCameraIsNeverUsedForBackgroundCrypto() {
        val main=Host();val camera=Host();val hosts=WeakHostRegistry<Host> { !it.destroyed }
        hosts.attach(main);hosts.attach(camera);camera.destroyed=true
        assertSame(main,hosts.current());main.destroyed=true;assertNull(hosts.current())
    }
    @Test fun detachingAnOldActivityCannotClearItsReplacement() {
        val old=Host();val replacement=Host();val hosts=WeakHostRegistry<Host> { !it.destroyed }
        hosts.attach(old);hosts.attach(replacement);hosts.detach(old)
        assertSame(replacement,hosts.current());hosts.attach(replacement);hosts.detach(replacement);assertNull(hosts.current())
    }
}
