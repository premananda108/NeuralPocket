package ua.pp.prema.NeuralPocket.engine

import android.content.Context
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test
import ua.pp.prema.NeuralPocket.engine.EngineErrorKind
import ua.pp.prema.NeuralPocket.engine.InferenceErrorKind
import ua.pp.prema.NeuralPocket.engine.LiteRtManager
import java.io.File

class LiteRtManagerTest {

    @Test
    fun `categorizeEngineError identifies OutOfMemory`() {
        val mockContext = mockk<Context>()
        val manager = LiteRtManager(mockContext)
        
        val exception = Exception("Failed to allocate 1024 bytes (out of memory)")
        val kind = manager.categorizeEngineError(exception, null)
        
        assertEquals(EngineErrorKind.OutOfMemory, kind)
    }

    @Test
    fun `categorizeEngineError identifies FileNotFound and deletes file`() {
        val mockContext = mockk<Context>()
        val manager = LiteRtManager(mockContext)
        
        // Mock file
        val mockFile = mockk<File>(relaxed = true)
        
        val exception = Exception("No such file or directory")
        val kind = manager.categorizeEngineError(exception, mockFile)
        
        assertEquals(EngineErrorKind.FileNotFound, kind)
        verify { mockFile.delete() }
    }

    @Test
    fun `categorizeEngineError identifies UnsupportedAbi`() {
        val mockContext = mockk<Context>()
        val manager = LiteRtManager(mockContext)
        
        val exception = Exception("unsupported abi: arm64-v8a required")
        val kind = manager.categorizeEngineError(exception, null)
        
        assertEquals(EngineErrorKind.UnsupportedAbi, kind)
    }

    @Test
    fun `categorizeInferenceError identifies ContextOverflow`() {
        val mockContext = mockk<Context>()
        val manager = LiteRtManager(mockContext)
        
        val exception = Exception("Context length exceeded limit")
        val kind = manager.categorizeInferenceError(exception)
        
        assertEquals(InferenceErrorKind.ContextOverflow, kind)
    }

    @Test
    fun `categorizeInferenceError identifies Generic error`() {
        val mockContext = mockk<Context>()
        val manager = LiteRtManager(mockContext)
        
        val exception = Exception("Something went terribly wrong")
        val kind = manager.categorizeInferenceError(exception)
        
        assertEquals(InferenceErrorKind.Generic("Something went terribly wrong"), kind)
    }
}
