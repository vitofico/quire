package io.theficos.ereader.core.metadata

import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The JVM's parser factory with one Android behaviour: `setXIncludeAware` throws whatever the
 * argument, as Android's DocumentBuilderFactory does. JVM tests otherwise pass code that fails
 * on every phone.
 */
class AndroidLikeDocumentBuilderFactory : DocumentBuilderFactory() {
    private val delegate = DocumentBuilderFactory.newDefaultInstance()

    override fun setXIncludeAware(state: Boolean) {
        throw UnsupportedOperationException("setXIncludeAware is not supported on Android")
    }

    override fun newDocumentBuilder(): DocumentBuilder {
        delegate.isNamespaceAware = isNamespaceAware
        delegate.isValidating = isValidating
        delegate.isExpandEntityReferences = isExpandEntityReferences
        return delegate.newDocumentBuilder()
    }

    override fun setAttribute(name: String, value: Any?) = delegate.setAttribute(name, value)

    override fun getAttribute(name: String): Any? = delegate.getAttribute(name)

    override fun setFeature(name: String, value: Boolean) = delegate.setFeature(name, value)

    override fun getFeature(name: String): Boolean = delegate.getFeature(name)
}
