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
//   slot <0-8>            send a held_item_slot packet
//   look <yaw> <pitch>    send a look packet
//   move <x> <y> <z>      send a position packet (player movement is client authoritative)
//   chat <text>           send a chat message
//   use                   send a use_item packet (right click / place / eat)
//   attack <entityId>     send a use_entity attack packet
//   quit                  disconnect
//
// The movement/interaction commands are the extension points for the gameplay
// compatibility rows (minecarts, tree felling, item merging, TPA, chat): the
// harness drives them and verifies the result on the other server. They are
// compile-checked only until a live cluster run records evidence.
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
// Weather and game-mode changes delivered as game_state_change: reason 1 begins
// rain, 2 ends rain, 3 changes game mode, 4 enters credits. The harness uses the
// rain reasons to verify master-sequenced weather independently of RCON output.
client.on('game_state_change', (packet) => {
  console.log(`[bot] GAMESTATE reason=${packet.reason} value=${packet.gameMode}`);
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
      } else if (command === 'look') {
        const [yaw, pitch] = line.split(/\s+/).slice(1).map(Number);
        client.write('look', { yaw, pitch, onGround: true });
        console.log(`[bot] LOOK ${yaw} ${pitch}`);
      } else if (command === 'move') {
        const [x, y, z] = line.split(/\s+/).slice(1).map(Number);
        client.write('position', { x, y, z, onGround: true });
        console.log(`[bot] MOVE ${x} ${y} ${z}`);
      } else if (command === 'chat') {
        const message = line.slice('chat '.length);
        client.write('chat', { message });
        console.log(`[bot] CHAT ${message}`);
      } else if (command === 'use') {
        client.write('use_item', { hand: 0 });
        console.log('[bot] USE');
      } else if (command === 'attack') {
        const target = parseInt(argument, 10);
        client.write('use_entity', { target, mouse: 1 });
        console.log(`[bot] ATTACK ${target}`);
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
