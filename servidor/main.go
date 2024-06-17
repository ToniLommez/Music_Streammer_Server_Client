package main

import (
	"bufio"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
)

const (
	TCP_ADDRESS = ":5050"
	UDP_ADDRESS = ":5051"
	BUFFER_SIZE = 4096
)

var (
	Musics map[string][]byte
)

type TCPServer struct {
	Addr string
}

type UDPServer struct {
	Addr string
}

func main() {
	Musics = make(map[string][]byte)

	tcpServer := TCPServer{Addr: TCP_ADDRESS}
	udpServer := UDPServer{Addr: UDP_ADDRESS}

	go tcpServer.Start()
	go udpServer.Start()

	select {}
}

func (s *TCPServer) Start() {
	var listener net.Listener
	var conn net.Conn
	var err error

	// Opening server
	if listener, err = net.Listen("tcp", s.Addr); err != nil {
		fmt.Println("error starting tcp server:", err)
		return
	}
	defer listener.Close()

	fmt.Println("tcp server listening on", s.Addr)

	for {
		if conn, err = listener.Accept(); err != nil {
			fmt.Println("error accepting tcp connection:", err)
			continue
		}
		go s.handleConnection(conn)
	}
}

func (s *TCPServer) handleConnection(conn net.Conn) {
	defer conn.Close()
	var sc *bufio.Scanner
	var response string
	var content []byte
	var music string
	var length int
	var err error

	// Read the music name from the segment
	sc = bufio.NewScanner(bufio.NewReader(conn))
	sc.Scan()
	music = sc.Text()
	if sc.Err() != nil {
		fmt.Println("error reading from tcp client:", err)
		return
	}
	fmt.Println("requested music: " + music + ".wav")

	// Open the music file
	if content, err = os.ReadFile(music + ".wav"); err != nil {
		response = "response: music not found\nsize: 0\npackets: 0"
	} else {
		length = len(content)
		response = fmt.Sprintf("response: music ready\nsize: %d\npackets: %d", length, length/BUFFER_SIZE)
		Musics[music] = content
	}

	// TCP response
	if _, err = conn.Write([]byte(response)); err != nil {
		fmt.Println("error sending tcp message:", err)
	}
}

// Start inicia o servidor UDP
func (s *UDPServer) Start() {
	var clientAddr *net.UDPAddr
	var addr *net.UDPAddr
	var conn *net.UDPConn
	var buffer []byte
	var err error
	var n int

	if addr, err = net.ResolveUDPAddr("udp", s.Addr); err != nil {
		fmt.Println("error resolving udp address:", err)
		return
	}

	if conn, err = net.ListenUDP("udp", addr); err != nil {
		fmt.Println("error starting udp server:", err)
		return
	}
	defer conn.Close()

	fmt.Println("udp server listening on", s.Addr)
	buffer = make([]byte, BUFFER_SIZE)

	for {
		if n, clientAddr, err = conn.ReadFromUDP(buffer); err == nil {
			go s.handleRequest(conn, clientAddr, buffer, n)
		} else {
			fmt.Println("error receiving udp request:", err)
		}
	}
}

// handleRequest manipula a solicitação UDP
func (s *UDPServer) handleRequest(conn *net.UDPConn, clientAddr *net.UDPAddr, buffer []byte, n int) {
	var request string
	var parts []string
	var packet []byte
	var bufNeedle int
	var music string
	var bufPos int
	var err error
	var pos int

	// application data
	request = strings.ReplaceAll(strings.ReplaceAll(string(buffer[:n]), "\r", ""), "\n", "")
	parts = strings.Split(request, ":")
	if len(parts) != 2 {
		fmt.Println("invalid request:", request)
		return
	}

	// receiving music name and position to send
	music = parts[0]
	if pos, err = strconv.Atoi(parts[1]); err != nil {
		fmt.Println("error converting position:", err)
		return
	}

	bufPos = pos * BUFFER_SIZE
	musicSize := len(Musics[music])
	bufNeedle = Min(musicSize, bufPos+BUFFER_SIZE)
	if bufPos+BUFFER_SIZE > musicSize {
		return
	}

	packet = append([]byte(fmt.Sprintf("%08d", pos)), Musics[music][bufPos:bufNeedle]...)
	_, err = conn.WriteToUDP(packet, clientAddr)
	if err != nil {
		fmt.Println("error sending udp datagram:", err)
		return
	}

	fmt.Printf("\rsending %d of %d              ", pos, (musicSize/BUFFER_SIZE)-1)
}

func Min(x, y int) int {
	return y ^ ((x ^ y) & ((x - y) >> 63))
}
