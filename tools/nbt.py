#!/usr/bin/env python3
"""A small read-only NBT parser, enough to walk chunk and entity data."""
import struct

TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class Reader:
    def __init__(self, data, pos=0):
        self.data = data
        self.pos = pos

    def u1(self):
        value = self.data[self.pos]
        self.pos += 1
        return value

    def take(self, fmt, size):
        value = struct.unpack_from(fmt, self.data, self.pos)[0]
        self.pos += size
        return value

    def string(self):
        length = self.take('>H', 2)
        value = self.data[self.pos:self.pos + length]
        self.pos += length
        return value.decode('utf-8', 'replace')

    def payload(self, tag):
        if tag == TAG_BYTE:
            return self.take('>b', 1)
        if tag == TAG_SHORT:
            return self.take('>h', 2)
        if tag == TAG_INT:
            return self.take('>i', 4)
        if tag == TAG_LONG:
            return self.take('>q', 8)
        if tag == TAG_FLOAT:
            return self.take('>f', 4)
        if tag == TAG_DOUBLE:
            return self.take('>d', 8)
        if tag == TAG_BYTE_ARRAY:
            n = self.take('>i', 4)
            value = self.data[self.pos:self.pos + n]
            self.pos += n
            return value
        if tag == TAG_STRING:
            return self.string()
        if tag == TAG_LIST:
            item = self.u1()
            n = self.take('>i', 4)
            return [self.payload(item) for _ in range(n)]
        if tag == TAG_COMPOUND:
            out = {}

            while True:
                child = self.u1()

                if child == TAG_END:
                    return out

                name = self.string()
                out[name] = self.payload(child)
        if tag == TAG_INT_ARRAY:
            n = self.take('>i', 4)
            value = list(struct.unpack_from('>%di' % n, self.data, self.pos))
            self.pos += n * 4
            return value
        if tag == TAG_LONG_ARRAY:
            n = self.take('>i', 4)
            value = list(struct.unpack_from('>%dq' % n, self.data, self.pos))
            self.pos += n * 8
            return value

        raise ValueError('unknown tag %d at %d' % (tag, self.pos))


def parse(data):
    """Parses a full NBT document and returns the root compound."""
    reader = Reader(data)
    tag = reader.u1()

    if tag != TAG_COMPOUND:
        raise ValueError('root is not a compound')

    reader.string()
    return reader.payload(TAG_COMPOUND)
