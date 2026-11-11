package dev.relay.tv.data

/**
 * Play entries for a list of channels, created only when something asks for them. A TV list can
 * hold tens of thousands of channels; the player needs the one playing and its neighbours, so
 * building every entry up front (as Playback used to) made pressing OK pause on weak TVs.
 */
class LazyItems private constructor(
    private val channels: List<Channel>,
    private val make: (Channel) -> PlayItem,
    private val cache: Array<PlayItem?>,
) : AbstractList<PlayItem>(), RandomAccess {

    constructor(channels: List<Channel>, make: (Channel) -> PlayItem) :
        this(channels, make, arrayOfNulls<PlayItem>(channels.size))

    override val size: Int get() = channels.size

    override fun get(index: Int): PlayItem {
        cache[index]?.let { return it }
        return make(channels[index]).also { cache[index] = it }
    }

    /** The same list with one entry replaced, for example by the next version of a channel. */
    fun with(index: Int, item: PlayItem): LazyItems {
        val copy = cache.copyOf()
        copy[index] = item
        return LazyItems(channels, make, copy)
    }

    /** Position of the channel with this number, without building any entries; -1 if none. */
    fun indexOfNumber(num: Int): Int = if (num <= 0) -1 else channels.indexOfFirst { it.num == num }

    /** Position of this channel, without building any entries; -1 if none. */
    fun indexOfChannel(id: Int): Int = channels.indexOfFirst { it.id == id }
}
