-- kb_net: gen1-bridge's TCP transport (docs/PROTOCOL.md "Transport").
--
-- gen1-bridge listens on 127.0.0.1:27151 and serves one Minecraft client at a
-- time; a newer connection replaces the older. Lines are newline-delimited
-- JSON, at most 4 MiB each. Everything here is non-blocking: pump() is called
-- once per logic step and never waits.
--
-- The socket module is injected (LuaSocket in game, a fake in tests) and only
-- resolved on first use, so this file loads without LuaSocket (modkit, tests).
--
-- LuaSocket 3.0-rc1 details this relies on (LÖVE 11.5's bundled copy):
--   * socket.bind() sets reuseaddr, which on Windows can let a second
--     listener share the port silently, so the listener is built by hand
--     with tcp() + bind() + listen() and no reuseaddr;
--   * an accepted client starts out blocking (timeout -1), so settimeout(0)
--     comes first, or the first receive would freeze the game;
--   * receive(n) on a non-blocking socket returns nil, "timeout", partial when
--     fewer than n bytes are waiting: the partial bytes are real data;
--   * send(s, i) returns the index of the last byte sent, or nil, err, last.

local Net = {}

local find, sub = string.find, string.sub
local concat = table.concat

Net.MAX_LINE = 4 * 1024 * 1024
Net.READ_SIZE = 8192
Net.READ_BUDGET = 256 * 1024      -- bytes read per pump, so a flood can't stall a step
Net.MAX_QUEUED = 32 * 1024 * 1024 -- a client this far behind is dropped
Net.COALESCE = 64 * 1024          -- queued lines are joined up to this size per send
Net.RETRY_SECONDS = 1
Net.FINISH_SECONDS = 2            -- finish() gives a slow reader this long to take the queue

-- ------------------------------------------------------------ framing

-- Splits a byte stream into lines. The pieces of an unfinished line are kept
-- in a list and joined once, when its newline arrives, so a 4 MiB line that
-- arrives 8 KiB at a time costs one copy, not hundreds. A trailing CR is
-- dropped (CRLF is tolerated) and empty lines are ignored.
function Net.newFramer(maxLine)
  local framer = { max = maxLine or Net.MAX_LINE }
  local parts, count, size = {}, 0, 0

  function framer.reset()
    parts, count, size = {}, 0, 0
  end

  function framer.pending() return size end

  -- emit(line) may return false to stop (the connection went away).
  -- Returns true, or nil plus "oversize" when a line exceeds the limit.
  function framer.push(data, emit)
    local start = 1
    local n = #data
    while start <= n do
      local nl = find(data, "\n", start, true)
      if not nl then
        local rest = sub(data, start)
        size = size + #rest
        if size > framer.max then framer.reset(); return nil, "oversize" end
        count = count + 1
        parts[count] = rest
        return true
      end
      local piece = sub(data, start, nl - 1)
      local line
      if count > 0 then
        count = count + 1
        parts[count] = piece
        line = concat(parts, "", 1, count)
        parts, count, size = {}, 0, 0
      else
        line = piece
      end
      if sub(line, -1) == "\r" then line = sub(line, 1, -2) end
      if #line > framer.max then framer.reset(); return nil, "oversize" end
      start = nl + 1
      if #line > 0 and emit(line) == false then return true end
    end
    return true
  end

  return framer
end

-- ------------------------------------------------------------ the listener

local BIND_ERRORS = {
  ["address already in use"] = "addr_in_use",
  ["permission denied"] = "access",
}

-- opts:
--   socket       the LuaSocket module, or a function returning it (lazy)
--   host, port   default 127.0.0.1:27151
--   clock        function returning seconds (love.timer.getTime in game)
--   log          a kb_log logger
--   onLine(line), onConnect(), onDisconnect(reason)   callbacks
--   onOversize()  called before a client whose line broke the size limit is
--                 finished, so the caller can queue an `error` first
--   maxLine, readBudget, maxQueued, retrySeconds      limits
function Net.new(opts)
  assert(type(opts) == "table", "kb_net.new expects options")
  local host = opts.host or "127.0.0.1"
  local port = opts.port or 27151
  local clock = assert(opts.clock, "kb_net needs a clock")
  local log = opts.log
  local maxLine = opts.maxLine or Net.MAX_LINE
  local readBudget = opts.readBudget or Net.READ_BUDGET
  local maxQueued = opts.maxQueued or Net.MAX_QUEUED
  local retrySeconds = opts.retrySeconds or Net.RETRY_SECONDS
  local onLine = opts.onLine or function() end
  local onConnect = opts.onConnect or function() end
  local onDisconnect = opts.onDisconnect or function() end
  local onOversize = opts.onOversize

  local net = {}
  local socketModule
  local server, client
  local closing, closeBy -- set by finish(): the reason, and the deadline
  local nextBindAt = 0
  local framer = Net.newFramer(maxLine)
  local queue, qhead, qtail, qbytes, offset = {}, 1, 0, 0, 1
  local stats = { accepted = 0, dropped = 0, linesIn = 0, linesOut = 0, bytesIn = 0, bytesOut = 0 }

  local function info(code, fields) if log then log.info(code, fields) end end
  local function once(key, level, code, fields) if log then log.once(key, level, code, fields) end end

  local function resolveSocket()
    if socketModule == nil then
      local source = opts.socket
      if type(source) == "function" then
        local ok, loaded = pcall(source)
        source = ok and loaded or nil
      end
      socketModule = type(source) == "table" and source or false
    end
    return socketModule or nil
  end

  local function clearQueue()
    queue, qhead, qtail, qbytes, offset = {}, 1, 0, 0, 1
  end

  local function dropClient(reason)
    if not client then return end
    local old = client
    client = nil
    closing, closeBy = nil, nil
    pcall(old.close, old)
    framer.reset()
    clearQueue()
    stats.dropped = stats.dropped + 1
    info("net.disconnected", { reason = reason })
    onDisconnect(reason)
  end

  local function bindFailed(stage, err)
    local code = BIND_ERRORS[err] or "other"
    nextBindAt = clock() + retrySeconds
    once("bind:" .. stage .. ":" .. code, "warn", "net.bind_failed",
      { stage = stage, err = code, port = port, retry = retrySeconds })
  end

  local function tryBind()
    local socket = resolveSocket()
    if not socket then
      nextBindAt = clock() + retrySeconds
      once("bind:nosocket", "error", "net.no_socket", {})
      return
    end
    local master, err = socket.tcp()
    if not master then return bindFailed("create", err) end
    master:settimeout(0)
    local ok
    ok, err = master:bind(host, port)
    if not ok then pcall(master.close, master); return bindFailed("bind", err) end
    ok, err = master:listen(4)
    if not ok then pcall(master.close, master); return bindFailed("listen", err) end
    server = master
    if log then
      log.rearm("bind:bind:addr_in_use")
      log.rearm("bind:bind:other")
    end
    info("net.listening", { host = host, port = port })
  end

  local function accept()
    local incoming, err = server:accept()
    if not incoming then
      if err ~= "timeout" then
        -- a broken listener is rebuilt on a later pump
        pcall(server.close, server)
        server = nil
        nextBindAt = clock() + retrySeconds
        once("accept:" .. tostring(err == "closed"), "warn", "net.accept_failed", {})
      end
      return
    end
    incoming:settimeout(0)
    pcall(incoming.setoption, incoming, "tcp-nodelay", true)
    if client then dropClient("replaced") end
    client = incoming
    stats.accepted = stats.accepted + 1
    info("net.connected", { count = stats.accepted })
    onConnect()
  end

  local function flush()
    while client and qhead <= qtail do
      -- join small queued lines so a burst costs one send, not dozens
      if offset == 1 and qtail > qhead and #queue[qhead] < Net.COALESCE then
        local joined, size, last = {}, 0, qhead
        while last <= qtail and size + #queue[last] <= Net.COALESCE do
          joined[#joined + 1] = queue[last]
          size = size + #queue[last]
          last = last + 1
        end
        if last - qhead > 1 then
          for i = qhead, last - 2 do queue[i] = nil end
          qhead = last - 1
          queue[qhead] = concat(joined)
        end
      end
      local data = queue[qhead]
      local sent, err, lastIndex = client:send(data, offset)
      if sent then
        stats.bytesOut = stats.bytesOut + (#data - offset + 1)
        qbytes = qbytes - #data
        queue[qhead] = nil
        qhead = qhead + 1
        offset = 1
      elseif err == "timeout" then
        local upTo = lastIndex or (offset - 1)
        stats.bytesOut = stats.bytesOut + (upTo - offset + 1)
        offset = upTo + 1
        return
      else
        dropClient(err == "closed" and "closed" or "send_error")
        return
      end
    end
    if qhead > qtail then clearQueue() end
  end

  local function receive()
    local budget = readBudget
    local current = client
    local function deliver(line)
      if client ~= current then return false end
      stats.linesIn = stats.linesIn + 1
      onLine(line)
      return client == current
    end
    while client == current and budget > 0 do
      local data, err, partial = current:receive(Net.READ_SIZE)
      local chunk = data or partial
      if chunk and #chunk > 0 then
        budget = budget - #chunk
        stats.bytesIn = stats.bytesIn + #chunk
        local ok, ferr = framer.push(chunk, deliver)
        if client ~= current then return end
        if not ok then
          if onOversize then pcall(onOversize) end
          net.finish(ferr)
          return
        end
      end
      if err == "closed" then dropClient("closed"); return end
      if err == "timeout" then return end
      if err then dropClient("receive_error"); return end
    end
  end

  -- One non-blocking turn: keep the listener up, take a new client, send
  -- what is queued, read and deliver complete lines. A finishing client is
  -- no longer read; it is closed once its queue has gone out.
  function net.pump()
    if not server and clock() >= nextBindAt then tryBind() end
    if server then accept() end
    if client then flush() end
    if client and not closing then receive() end
    if client then flush() end
    if client and closing and (qhead > qtail or clock() >= closeBy) then
      dropClient(closing)
    end
  end

  -- Sends what is queued without reading: for lines queued after this
  -- step's pump, so they don't wait a step.
  function net.flush()
    if client then flush() end
    if client and closing and qhead > qtail then dropClient(closing) end
  end

  -- Stops reading from the client and closes it once everything queued so
  -- far has been sent (or after FINISH_SECONDS), e.g. after a hello.reject.
  function net.finish(reason)
    if not client or closing then return end
    closing = reason or "finished"
    closeBy = clock() + Net.FINISH_SECONDS
    flush()
    if client and qhead > qtail then dropClient(closing) end
  end

  -- Queues one line (a newline is added). False when no client is connected
  -- or it is finishing.
  function net.send(line)
    assert(type(line) == "string", "kb_net.send expects a string")
    if find(line, "\n", 1, true) then error("kb_net.send: line contains a newline", 2) end
    if not client or closing then return false end
    if #line + 1 > maxLine then
      once("send:oversize", "error", "net.send_oversize", { bytes = #line })
      return false
    end
    qtail = qtail + 1
    queue[qtail] = line .. "\n"
    qbytes = qbytes + #line + 1
    stats.linesOut = stats.linesOut + 1
    if qbytes > maxQueued then dropClient("backlog") return false end
    return true
  end

  function net.connected() return client ~= nil and not closing end
  function net.listening() return server ~= nil end
  function net.port() return port end

  -- Moves the listener to another port: the current listener and client are
  -- closed and the new port is bound on the next pump.
  function net.setPort(newPort)
    assert(type(newPort) == "number" and newPort >= 1 and newPort <= 65535 and newPort % 1 == 0,
      "kb_net.setPort expects a port number")
    if newPort == port then return end
    dropClient("reconfigured")
    if server then pcall(server.close, server); server = nil end
    port = newPort
    nextBindAt = 0
    if log then
      log.rearm("bind:bind:addr_in_use")
      log.rearm("bind:bind:other")
    end
    info("net.port_changed", { port = port })
  end
  function net.queued() return qbytes end
  function net.disconnect(reason) dropClient(reason or "local") end

  function net.close()
    dropClient("shutdown")
    if server then pcall(server.close, server); server = nil end
  end

  function net.stats()
    local out = {}
    for k, v in pairs(stats) do out[k] = v end
    out.listening, out.connected, out.queued = server ~= nil, client ~= nil, qbytes
    return out
  end

  return net
end

return Net
