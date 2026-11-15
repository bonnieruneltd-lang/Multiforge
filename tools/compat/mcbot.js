// Headless player bot used by tools/compat/player_state_check.py.
//
// It connects to a MultiForge server with a vanilla 1.20.1 client protocol
// implementation, stays connected, and accepts a few commands on a local control
// socket so a test can change state the way a real client would (for example the
// selected hotbar slot, which no vanilla command can set on a player).
//
// Install (development tooling, not shipped with the mod):
//   npm install minecraft-protocol@1.68.0
//
// Usage:
//   node mcbot.js <host> <port> <username> [controlPort]
//   node mcbot.js 127.0.0.1 25565 MirrorBot 25599
//
// Control socket commands (one per line on 127.0.0.1:<controlPort>):
//   slot <0-8>   send a held_item_slot packet
//   quit         disconnect
const mc = require('minecraft-protocol');
const net = require('net');

const host = process.argv[2] || '127.0.0.1';
const port = parseInt(process.argv[3] || '25565', 10);
const username = process.argv[4] || 'MirrorBot';
const controlPort = parseInt(process.argv[5] || '25599', 10);

const client = mc.createClient({
  host,
  port,
  username,
  auth: 'offline',
  version: '1.20.1'
});

client.on('login', () => {
  console.log(`[bot] LOGIN ${username} entity=${client.entity ? client.entity.id : '?'}`);
});
client.on('spawn', () => {
  const p = client.entity && client.entity.position;
  console.log(`[bot] SPAWN ${p ? `${p.x},${p.y},${p.z}` : ''}`);
});
client.on('error', (error) => console.log(`[bot] ERROR ${error.message}`));
client.on('end', (reason) => {
  console.log(`[bot] END ${reason}`);
  process.exit(0);
});

net.createServer((socket) => {
  socket.setEncoding('utf8');
  let buffer = '';
  socket.on('data', (chunk) => {
    buffer += chunk;
    let index;
    while ((index = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, index).trim();
      buffer = buffer.slice(index + 1);
      if (!line) continue;
      const [command, argument] = line.split(/\s+/);
      if (command === 'slot') {
        const slot = parseInt(argument, 10);
        client.write('held_item_slot', { slotId: slot });
        console.log(`[bot] SLOT ${slot}`);
      } else if (command === 'quit') {
        console.log('[bot] QUIT');
        client.end();
      } else {
        console.log(`[bot] UNKNOWN ${line}`);
      }
    }
  });
}).listen(controlPort, '127.0.0.1', () => {
  console.log(`[bot] CONTROL ${controlPort}`);
});

// Keep the connection alive; minecraft-protocol answers keep-alives itself.
setInterval(() => {}, 60 * 60 * 1000);
