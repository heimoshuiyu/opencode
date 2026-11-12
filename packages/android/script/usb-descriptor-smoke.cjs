// Run inside the workspace via oc-usb-run. Never opens a device path or consumes MCP metadata.
const fs = require('node:fs')
const assert = require('node:assert/strict')
const fd = Number(process.env.OPENCODE_USB_FD)
assert.equal(fd, 3)
const data = Buffer.alloc(27) // device descriptor followed by the first configuration header
try {
  assert.ok(fs.fstatSync(fd).isCharacterDevice())
  assert.equal(fs.readSync(fd, data, 0, data.length, 0), data.length)
  assert.deepEqual([...data.subarray(0, 2)], [18, 1])
  assert.deepEqual([...data.subarray(18, 20)], [9, 2])
} finally {
  fs.closeSync(fd)
}
assert.throws(() => fs.fstatSync(fd), { code: 'EBADF' })
console.log(JSON.stringify({ pid: process.pid, descriptor: data.subarray(0, 18).toString('hex'),
  vendor: data.readUInt16LE(8), product: data.readUInt16LE(10), interfaces: data[22], afterClose: 'EBADF' }))
