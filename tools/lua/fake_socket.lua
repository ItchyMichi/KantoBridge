-- A fake LuaSocket for gen1-bridge's tests: the TCP calls gen1-bridge makes,
-- with the exact return values of LuaSocket 3.0-rc1 as bundled in LÖVE 11.5
-- (read in refs/gen1recomp/mobile/android/love/src/jni/love/src/libraries/
-- luasocket/libluasocket/: tcp.c, buffer.c, io.c, pierror.h, socket.lua).
-- Written for Kanto Bridge.
--
-- Semantics kept on purpose:
--   * accept() on a server with settimeout(0): a client, or nil, "timeout";
--   * an accepted client starts BLOCKING (tcp.c meth_accept: timeout -1), and
--     a call that would block raises here, because in game it freezes;
--   * receive(n): data; or nil, "timeout"|"closed", partial (buffer.c);
--     "*l" drops CRs and also hands back its partial line on timeout;
--   * send(s, i, j): index of the last byte sent; or nil, err, last;
--   * bind() on a busy port: nil, "address already in use";
--   * socket.bind() sets reuseaddr (socket.lua), which tests can detect.
--
-- Test side:
--   local fake = FakeSocket.new()
--   fake.socket                -- what require("socket") returns
--   local peer = fake.net:connect("127.0.0.1", 27151)   -- a remote client
--   peer:send("line\n"); peer:take() -> bytes the server sent; peer:close()
--   peer:setWindow(n)          -- server may send n more bytes, then "timeout"
--   fake.net:occupy(host, port) -- another process holds the port

local FakeSocket = {}

local function checkClass(obj, cls, method)
  if type(obj) ~= "table" or obj._class ~= cls then
    error(("bad argument #1 to '%s' (%s expected, got %s)")
      :format(method, cls, type(obj) == "table" and tostring(obj._class) or type(obj)), 3)
  end
end

local function checkAny(obj, method)
  if type(obj) ~= "table" or not obj._class then
    error(("bad argument #1 to '%s' (tcp{any} expected)"):format(method), 3)
  end
end

function FakeSocket.new()
  local net = { listeners = {}, occupied = {}, time = 0, nextPort = 50000,
                created = 0, reuseaddrSet = 0, bindHelperUsed = 0, accepted = 0 }
  local socket = { _VERSION = "LuaSocket 3.0-rc1" }

  local function key(host, port) return tostring(host) .. ":" .. tostring(port) end

  -- ------------------------------------------------------------ objects

  local Tcp = {}
  Tcp.__index = Tcp

  local function newTcp(class)
    return setmetatable({ _class = class, timeout = -1, options = {}, closed = false }, Tcp)
  end

  function Tcp:settimeout(t, mode)
    checkAny(self, "settimeout")
    mode = mode or "b"
    if mode ~= "b" and mode ~= "t" and mode ~= "r" then
      error("bad argument #3 to 'settimeout' (invalid timeout mode)", 2)
    end
    if mode == "b" then self.timeout = t or -1 else self.total = t or -1 end
    return 1
  end

  function Tcp:gettimeout() return self.timeout, self.total or -1 end

  function Tcp:setoption(name, value)
    checkAny(self, "setoption")
    self.options[name] = value
    if name == "reuseaddr" and value then net.reuseaddrSet = net.reuseaddrSet + 1 end
    return 1
  end

  function Tcp:getoption(name) return self.options[name] end

  function Tcp:bind(host, port)
    checkClass(self, "tcp{master}", "bind")
    if type(host) ~= "string" then error("bad argument #2 to 'bind' (string expected)", 2) end
    if type(port) ~= "string" and type(port) ~= "number" then
      error("bad argument #3 to 'bind' (string expected)", 2)
    end
    port = tonumber(port)
    if port == 0 then
      net.nextPort = net.nextPort + 1
      port = net.nextPort
    end
    local k = key(host, port)
    if net.occupied[k] or net.listeners[k] then return nil, "address already in use" end
    self.host, self.port = host, port
    return 1
  end

  function Tcp:listen(backlog)
    checkClass(self, "tcp{master}", "listen")
    if not self.port then return nil, "invalid argument" end
    local k = key(self.host, self.port)
    if net.listeners[k] then return nil, "address already in use" end
    self._class = "tcp{server}"
    self.pending = {}
    self.backlog = backlog or 32
    net.listeners[k] = self
    return 1
  end

  function Tcp:getsockname()
    if not self.port then return nil, "not bound" end
    return self.host, self.port, "inet"
  end

  function Tcp:accept()
    checkClass(self, "tcp{server}", "accept")
    if self.closed then return nil, "closed" end
    local conn = table.remove(self.pending, 1)
    if not conn then
      if self.timeout < 0 then
        error("fake socket: accept() would block forever (call settimeout(0) first)", 2)
      end
      return nil, "timeout"
    end
    local client = newTcp("tcp{client}")
    client.conn = conn
    client.host, client.port = self.host, self.port
    conn.server = client
    net.accepted = net.accepted + 1
    return client
  end

  local function wouldBlock(self, what)
    if self.timeout < 0 then
      error(("fake socket: %s would block (the socket is still blocking; call settimeout(0))")
        :format(what), 3)
    end
  end

  -- buffer.c buffer_meth_receive
  function Tcp:receive(pattern, prefix)
    checkClass(self, "tcp{client}", "receive")
    prefix = prefix or ""
    local conn = self.conn
    if self.closed then return nil, "closed", prefix end
    pattern = pattern or "*l"
    if type(pattern) == "number" then
      if pattern < 0 then error("bad argument #2 to 'receive' (invalid receive pattern)", 2) end
      local need = math.floor(pattern) - #prefix
      if need <= 0 then return prefix end
      if #conn.toServer >= need then
        local data = conn.toServer:sub(1, need)
        conn.toServer = conn.toServer:sub(need + 1)
        return prefix .. data
      end
      local partial = conn.toServer
      conn.toServer = ""
      if conn.peerClosed then return nil, "closed", prefix .. partial end
      if #partial == 0 then wouldBlock(self, "receive") end
      if self.timeout < 0 then
        -- blocking with too little data: it would wait for the rest
        conn.toServer = partial
        wouldBlock(self, "receive")
      end
      return nil, "timeout", prefix .. partial
    end
    if pattern == "*l" or pattern:sub(1, 2) == "*l" then
      local nl = conn.toServer:find("\n", 1, true)
      if nl then
        local line = conn.toServer:sub(1, nl - 1):gsub("\r", "")
        conn.toServer = conn.toServer:sub(nl + 1)
        return prefix .. line
      end
      local partial = conn.toServer:gsub("\r", "")
      conn.toServer = ""
      if conn.peerClosed then return nil, "closed", prefix .. partial end
      wouldBlock(self, "receive")
      return nil, "timeout", prefix .. partial
    end
    if pattern == "*a" or pattern:sub(1, 2) == "*a" then
      local all = conn.toServer
      conn.toServer = ""
      if conn.peerClosed then
        if #all > 0 or #prefix > 0 then return prefix .. all end
        return nil, "closed", prefix
      end
      wouldBlock(self, "receive")
      return nil, "timeout", prefix .. all
    end
    error("bad argument #2 to 'receive' (invalid receive pattern)", 2)
  end

  -- buffer.c buffer_meth_send
  function Tcp:send(data, i, j)
    checkClass(self, "tcp{client}", "send")
    if type(data) ~= "string" then error("bad argument #2 to 'send' (string expected)", 2) end
    local size = #data
    local start, finish = i or 1, j or -1
    if start < 0 then start = size + start + 1 end
    if finish < 0 then finish = size + finish + 1 end
    if start < 1 then start = 1 end
    if finish > size then finish = size end
    local conn = self.conn
    if self.closed then return nil, "closed", start - 1 end
    if start > finish then return start - 1 end
    if conn.peerClosed then return nil, "closed", start - 1 end
    local count = finish - start + 1
    local allowed = count
    if conn.window then allowed = math.min(count, conn.window) end
    if allowed < count then
      if self.timeout < 0 then
        error("fake socket: send would block (the socket is still blocking; call settimeout(0))", 2)
      end
      conn.fromServer[#conn.fromServer + 1] = data:sub(start, start + allowed - 1)
      conn.window = conn.window - allowed
      conn.sendCalls = conn.sendCalls + 1
      return nil, "timeout", start + allowed - 1
    end
    conn.fromServer[#conn.fromServer + 1] = data:sub(start, finish)
    if conn.window then conn.window = conn.window - count end
    conn.sendCalls = conn.sendCalls + 1
    return finish
  end

  function Tcp:close()
    checkAny(self, "close")
    if not self.closed then
      self.closed = true
      if self._class == "tcp{server}" then
        local k = key(self.host, self.port)
        if net.listeners[k] == self then net.listeners[k] = nil end
      elseif self.conn then
        self.conn.serverClosed = true
      end
    end
    return 1
  end

  function Tcp:shutdown() return 1 end
  function Tcp:getpeername()
    checkClass(self, "tcp{client}", "getpeername")
    return "127.0.0.1", self.conn.peerPort, "inet"
  end
  function Tcp:dirty() return self.conn ~= nil and #self.conn.toServer > 0 end
  function Tcp:getfamily() return "inet" end
  Tcp.__tostring = function(self) return self._class .. ": fake" end

  -- ------------------------------------------------------------ module

  function socket.tcp()
    net.created = net.created + 1
    return newTcp("tcp{master}")
  end
  socket.tcp4 = socket.tcp
  function socket.gettime() return net.time end

  -- socket.lua _M.bind: sets reuseaddr, which gen1-bridge must avoid
  function socket.bind(host, port, backlog)
    net.bindHelperUsed = net.bindHelperUsed + 1
    local sock = socket.tcp()
    sock:setoption("reuseaddr", true)
    local ok, err = sock:bind(host, port)
    if not ok then sock:close() return nil, err end
    ok, err = sock:listen(backlog)
    if not ok then sock:close() return nil, err end
    return sock
  end

  -- ------------------------------------------------------------ test side

  local Peer = {}
  Peer.__index = Peer

  function net:connect(host, port)
    local listener = net.listeners[key(host, port)]
    if not listener or listener.closed then return nil, "connection refused" end
    net.nextPort = net.nextPort + 1
    local conn = { toServer = "", fromServer = {}, peerClosed = false,
                   serverClosed = false, window = nil, sendCalls = 0, peerPort = net.nextPort }
    table.insert(listener.pending, conn)
    return setmetatable({ conn = conn }, Peer)
  end

  function net:occupy(host, port) net.occupied[key(host, port)] = true end
  function net:free(host, port) net.occupied[key(host, port)] = nil end
  function net:listening(host, port)
    local l = net.listeners[key(host, port)]
    return l ~= nil and not l.closed
  end

  function Peer:send(data)
    assert(not self.conn.peerClosed, "peer already closed")
    self.conn.toServer = self.conn.toServer .. data
  end

  -- everything the server has sent so far, removed from the pipe
  function Peer:take()
    local out = table.concat(self.conn.fromServer)
    self.conn.fromServer = {}
    return out
  end

  function Peer:close() self.conn.peerClosed = true end
  function Peer:accepted() return self.conn.server ~= nil end
  function Peer:closedByServer() return self.conn.serverClosed end
  function Peer:setWindow(n) self.conn.window = n end
  function Peer:sendCalls() return self.conn.sendCalls end
  function Peer:pendingToServer() return #self.conn.toServer end

  return { socket = socket, net = net }
end

return FakeSocket
