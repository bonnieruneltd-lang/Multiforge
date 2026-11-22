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
//   chat <text>           send a signed chat message or a /command through client.chat
//   command <text>        same, but always sends the slash form
//   use                   send a use_item packet (right click / place / eat)
//   dig <x> <y> <z>       start and stop breaking a block
//   place <x> <y> <z>     place the selected item against a block
//   drop [all]            drop one item or the whole stack
//   attack <entityId>     send a use_entity attack packet
//   attackplayer <name>   attack a tracked player by name (player_info + named_entity_spawn)
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
// Chat/system output is needed by the gameplay checks (TPA, chat formatting,
// anti-seed). Log the text forms a vanilla client would display.
client.on('chat', (packet) => {
  console.log(`[bot] CHATIN ${typeof packet.message === 'string' ? packet.message : JSON.stringify(packet)}`);
});
client.on('system_chat', (packet) => {
  console.log(`[bot] SYSTEM ${typeof packet.content === 'string' ? packet.content : JSON.stringify(packet.content || packet)}`);
});
client.on('player_chat', (packet) => {
  console.log(`[bot] PLAYERCHAT ${JSON.stringify(packet)}`);
});
client.on('action_bar', (packet) => {
  console.log(`[bot] ACTIONBAR ${JSON.stringify(packet.text || packet)}`);
});
client.on('boss_bar', (packet) => {
  console.log(`[bot] BOSSBAR ${JSON.stringify({
    uuid: packet.entityUUID,
    action: packet.action,
    title: packet.title,
    health: packet.health,
    color: packet.color,
    division: packet.division,
    flags: packet.flags
  })}`);
});

// Minimal entity index: minecraft-protocol does not track entities itself, so
// the gameplay checks record player UUIDs from player_info and their entity ids
// from named_entity_spawn.
const playersByName = {};
const entityByUuid = {};
client.on('player_info', (packet) => {
  const entries = Array.isArray(packet.data) ? packet.data : [];
  for (const entry of entries) {
    const name = entry.name || entry.profileName;
    const uuid = entry.UUID || entry.uuid || entry.id;
    if (name && uuid) playersByName[name] = uuid;
  }
});
client.on('named_entity_spawn', (packet) => {
  if (packet.playerUUID) entityByUuid[packet.playerUUID] = packet.entityId;
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
        // client.chat handles signed chat/command packets and the slash form;
        // writing the raw 'chat' packet for a command is rejected by 1.20.1.
        client.chat(message);
        console.log(`[bot] CHAT ${message}`);
      } else if (command === 'command') {
        const message = line.slice('command '.length);
        client.chat(message.startsWith('/') ? message : `/${message}`);
        console.log(`[bot] COMMAND ${message}`);
      } else if (command === 'use') {
        client.write('use_item', { hand: 0 });
        console.log('[bot] USE');
      } else if (command === 'attack') {
        const target = parseInt(argument, 10);
        client.write('use_entity', { target, mouse: 1 });
        console.log(`[bot] ATTACK ${target}`);
      } else if (command === 'dig') {
        const [x, y, z, face] = line.split(/\s+/).slice(1).map(Number);
        const location = { x, y, z };
        client.write('block_dig', { status: 0, location, face: face || 1, sequence: 0 });
        setTimeout(() => {
          client.write('block_dig', { status: 2, location, face: face || 1, sequence: 0 });
          console.log(`[bot] DIG ${x} ${y} ${z}`);
        }, 900);
      } else if (command === 'place') {
        const [x, y, z, face] = line.split(/\s+/).slice(1).map(Number);
        client.write('block_place', {
          hand: 0,
          location: { x, y, z },
          direction: face || 1,
          cursorX: 0.5,
          cursorY: 0.5,
          cursorZ: 0.5,
          insideBlock: false,
          sequence: 0
        });
        console.log(`[bot] PLACE ${x} ${y} ${z}`);
      } else if (command === 'drop') {
        client.write('block_dig', {
          status: argument === 'all' ? 4 : 3,
          location: { x: 0, y: 0, z: 0 },
          face: 0,
          sequence: 0
        });
        console.log(`[bot] DROP ${argument || 'one'}`);
      } else if (command === 'attackplayer') {
        const name = argument;
        let hits = 0;
        const timer = setInterval(() => {
          const uuid = playersByName[name];
          let targetId = uuid ? entityByUuid[uuid] : undefined;
          if (targetId === undefined) {
            // If player_info has not been decoded yet, fall back to the only
            // spawned player entity that is not this bot.
            const own = client.uuid || '';
            const candidates = Object.entries(entityByUuid)
              .filter(([candidate]) => candidate !== own && candidate !== own.replace(/-/g, ''));
            if (candidates.length === 1) targetId = candidates[0][1];
          }
          if (targetId === undefined || hits++ > 20) {
            clearInterval(timer);
            console.log(`[bot] ATTACKPLAYER_END ${name}`);
            return;
          }
          client.write('use_entity', { target: targetId, mouse: 1 });
          console.log(`[bot] ATTACKPLAYER ${name} ${targetId}`);
        }, 700);
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
