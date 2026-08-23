đọc code HSS và đưa tôi call flos cho IMSI register [https://github.com/open5gs/open5gs/blob/main/src/hss/app-init.c](https://github.com/open5gs/open5gs/blob/main/src/hss/app-init.c)

Searched the web

### Tổng quan file `app-init.c`

File này **không chứa logic xử lý IMSI Register** — nó chỉ là entry point khởi tạo/tắt tiến trình HSS:

c

```c
int app_initialize(const char *const argv[])
{
    int rv;
    rv = hss_initialize();   // gọi vào lib/hss context + s6a/cx/swx setup
    ...
}

void app_terminate(void)
{
    hss_terminate();
}
```

Logic xử lý message S6a (AIR/AIA, ULR/ULA...) — tức là phần thực sự xử lý "IMSI Register" (Update Location trong Attach Procedure) — nằm ở file khác: `src/hss/hss-s6a-path.c`.

### Call flow IMSI Register (Attach → S6a) trong HSS

Khi UE Attach, MME gửi 2 cặp message Diameter S6a tới HSS, xử lý tuần tự:

**1. Authentication-Information-Request/Answer (AIR/AIA)**

- Callback: `hss_ogs_diam_s6a_air_cb()` trong `hss-s6a-path.c`
- Luồng xử lý:
  - Parse AVP `User-Name` (IMSI) từ AIR
  - Query subscriber từ MongoDB qua `hss_db_auth_info()` (lấy K, OPc, SQN...)
  - Sinh Authentication Vector (AV) bằng Milenage/`ogs_milenage_generate()`
  - Đóng gói AV vào `E-UTRAN-Vector` AVP
  - Trả về AIA với `Result-Code = DIAMETER_SUCCESS`

**2. Update-Location-Request/Answer (ULR/ULA)**

- Callback: `hss_ogs_diam_s6a_ulr_cb()` trong `hss-s6a-path.c`
- Luồng xử lý:
  - Parse IMSI (`User-Name`), `Origin-Host`/`Origin-Realm` (định danh MME)
  - Ghi `Origin-Host`/`Origin-Realm` vào DB làm `mme_host`/`mme_realm` (serving MME hiện tại của subscriber) — **lưu ý:** đây chính là chỗ bị báo lỗ hổng bảo mật trong Issue #4337, vì HSS **không xác thực** MME gửi ULR có được phép phục vụ subscriber đó hay không, chỉ đơn giản ghi đè.
  - Query subscription data từ DB: `hss_db_subscription_data()`
  - Build AVP `Subscription-Data` (APN config, AMBR, QoS profile, EPS-only/CSFB flag...)
  - Trả về ULA với `Result-Code = DIAMETER_SUCCESS` + Subscription-Data đầy đủ

Sơ đồ tổng thể:

```
UE            MME                         HSS (hss-s6a-path.c)
 |             |                              |
 |--Attach---->|                              |
 |             |--AIR (IMSI)----------------->| hss_ogs_diam_s6a_air_cb()
 |             |                              |   → hss_db_auth_info() (MongoDB)
 |             |                              |   → tạo AV (Milenage)
 |             |<--AIA (E-UTRAN-Vector)-------|
 |<--Auth Req--|                              |
 |--Auth Resp->|                              |
 |             |--ULR (IMSI, Origin-Host)---->| hss_ogs_diam_s6a_ulr_cb()
 |             |                              |   → ghi mme_host/realm vào DB
 |             |                              |   → hss_db_subscription_data()
 |             |<--ULA (Subscription-Data)----|
 |<--Attach Accept--                          |
```

Muốn mình lấy full source code của `hss-s6a-path.c` (khoảng vài trăm dòng) để bạn xem chi tiết từng hàm không? GitHub hiện chặn crawl trực tiếp trang `tree/blob` qua công cụ tìm kiếm này nên mình phải tra qua kết quả search/issue liên quan — nếu cần bản đầy đủ mình có thể thử tiếp qua [`raw.githubusercontent.com`](http://raw.githubusercontent.com).

kiểm tra tại sao hàm hss_ogs_diam_s6a_air_cb nhận struct msg **msg, tại sao k là *msg mà lại là* *

Searched the web

Lý do là do **freeDiameter's dispatch callback contract**, không phải chuyện riêng của Open5GS. Đây là kiểu callback chuẩn của thư viện freeDiameter (`fd_disp_call_cb_int` gọi vào), và signature bắt buộc là:

c

```c
int (*msg_hdl_cb)(struct msg **msg, struct avp *avp,
                   struct session *session, void *opaque,
                   enum disp_action *act);
```

**Tại sao** `struct msg **msg` **chứ không phải** `struct msg *msg`**:**

1. **Callback có quyền sở hữu và thay thế con trỏ message, không chỉ đọc nó**  
freeDiameter thiết kế để callback có thể **build câu trả lời (answer) ngay trên chính request đó** bằng cách gọi `ogs_diam_message_copy()` / `fd_msg_new_answer_from_req(fd_g_config->cnf_dict, msg, 0)`. Hàm `fd_msg_new_answer_from_req()` nhận `struct msg **msg` và **ghi đè lại chính con trỏ đó** — biến request ban đầu thành answer message tại chỗ (in-place). Nếu chỉ truyền `struct msg *msg` (pass-by-value), hàm callee không thể thay đổi con trỏ mà caller đang giữ — con trỏ answer mới sẽ bị mất khi ra khỏi scope. Ví dụ thực tế trong `hss_ogs_diam_s6a_air_cb`:

c

```c
   ret = fd_msg_new_answer_from_req(fd_g_config->cnf_dict, msg, 0);
   // *msg giờ trỏ tới answer message, không còn là request nữa
```

2. **Callback có thể "nuốt" message và trả NULL để báo hiệu đã xử lý xong / đã free**  
Sau khi gọi `fd_msg_send()` để gửi answer đi, framework cần biết message đã được gửi/giải phóng để không xử lý/free nó lần nữa ở tầng dispatch phía trên. Việc set `*msg = NULL` là tín hiệu "tôi (callback) đã lo xong vòng đời của message này rồi".
3. **Đây là pattern chuẩn out-parameter trong C**  
Khi một hàm cần "trả về" một con trỏ mới (hoặc null hóa con trỏ hiện có) cho caller, C không có cách nào khác ngoài truyền địa chỉ của con trỏ đó — tức `struct msg **`. Tương tự như `int foo(char **buf)` để hàm có thể `*buf = malloc(...)`.

Tóm lại: `**msg` không phải để "đọc message 2 lớp", mà vì **callback cần ghi đè con trỏ msg gốc** (biến request thành answer, hoặc null hóa sau khi gửi) — và cách duy nhất để một hàm C thay đổi giá trị con trỏ mà caller nhìn thấy là truyền `pointer-to-pointer`.

Thought for 5s

## MME↔eNB (S1-MME) và kiến trúc `epc-up-linux`

### Phần 1: MME kết nối với eNB như thế nào

Trước tiên cần làm rõ: vì **MME thuê từ MNO**, toàn bộ phần này **nằm ngoài phạm vi bạn phải build hay vận hành** — MNO tự quản lý S1-MME với eNB của họ. Nhưng bạn vẫn cần hiểu rõ luồng này vì nó quyết định **điều gì MME sẽ gửi xuống** `epc-sgwc` **của bạn qua S11**, và giúp debug khi có sự cố (VD: UE attach fail — cần biết lỗi nằm ở đoạn S1-MME hay S11).

#### Giao thức &amp; transport

```
eNB ──────────── S1-MME ──────────── MME
      SCTP (port 36412)
      giao thức ứng dụng: S1AP (S1 Application Protocol)
```

- **Transport**: SCTP (Stream Control Transmission Protocol) — không phải TCP/UDP. SCTP được chọn vì hỗ trợ **multi-homing** (nhiều IP path cho 1 association, tự failover) và **multi-streaming** (nhiều luồng độc lập trong 1 kết nối, tránh head-of-line blocking mà TCP gặp phải).
- **Port chuẩn**: 36412
- **Giao thức tầng ứng dụng**: **S1AP** (3GPP TS 36.413) — mã hoá ASN.1 PER

#### Các thủ tục chính trên S1-MME


| Thủ tục                                                          | Chiều   | Mục đích                                                                                                                                                                                                   |
| ---------------------------------------------------------------- | ------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **S1 Setup Request/Response**                                    | eNB→MME | eNB khởi tạo, quảng bá TAI (Tracking Area) nó phục vụ, MME trả về PLMN được hỗ trợ                                                                                                                         |
| **Initial UE Message**                                           | eNB→MME | Gói NAS message đầu tiên từ UE (Attach Request/TAU Request) khi UE chưa có context ở MME                                                                                                                   |
| **Downlink/Uplink NAS Transport**                                | 2 chiều | "Đóng gói" NAS message (EMM/ESM) giữa UE↔MME, xuyên qua eNB mà eNB không đọc nội dung (NAS được mã hoá end-to-end UE-MME)                                                                                  |
| **Initial Context Setup Request/Response**                       | MME→eNB | MME yêu cầu eNB thiết lập radio bearer + security context sau khi Attach được authorize — **đây là bước MME gửi S1-U F-TEID (địa chỉ/TEID GTP-U phía SGW-U) xuống eNB** để eNB biết gửi uplink data đi đâu |
| **UE Context Release**                                           | 2 chiều | Giải phóng context khi UE detach/idle                                                                                                                                                                      |
| **Handover procedures** (Path Switch, Handover Required/Command) | 2 chiều | Chuyển giao giữa các eNB (X2) hoặc qua MME (S1 handover)                                                                                                                                                   |
| **Paging**                                                       | MME→eNB | Đánh thức UE đang idle khi có downlink data/signaling                                                                                                                                                      |
| **E-RAB Setup/Modify/Release**                                   | MME→eNB | Quản lý bearer radio tương ứng với bearer EPS                                                                                                                                                              |


#### Sequence tối giản: từ Attach Request tới lúc data chạy

```
UE          eNB              MME              epc-sgwc (của bạn)
 │           │                 │                    │
 │─Attach───►│                 │                    │
 │  Request  │─Initial UE Msg─►│                    │
 │           │  (NAS: Attach)  │                    │
 │           │                 │──S6a AIR/ULR──► epc-hss
 │           │                 │◄──AIA/ULA──────────┘
 │           │                 │──S11 Create Session Req──►│
 │           │                 │◄──S11 Create Session Rsp──│ (kèm SGW S1-U F-TEID)
 │           │◄─Initial Context Setup Req──│                
 │           │  (kèm SGW S1-U F-TEID + security)            
 │◄──Attach Accept (qua eNB)───│                    
 │─Attach Complete─────────────►│                    
 │           │─Initial Context Setup Rsp─►│ (kèm eNB S1-U F-TEID)
 │           │                 │──S11 Modify Bearer Req──►│  (truyền eNB F-TEID)
 │           │                 │◄──S11 Modify Bearer Rsp──│
 │═══data════│════S1-U (GTP-U)═══════════════════════════│═S5-U═►PGW-U═►Internet
```

**Điểm mấu chốt cho kiến trúc của bạn**: `epc-sgwc` **không bao giờ thấy S1AP hay NAS** — nó chỉ nhận **S11 (GTPv2-C)** từ MME, trong đó MME đã "dịch" thông tin cần thiết (F-TEID của eNB cho S1-U) thành AVP/IE trong message GTPv2-C. Đây chính là lý do trong Mermaid diagram bạn vẽ ở tài liệu, `epc-sgwc` chỉ có mũi tên S11 tới MME, không có gì liên quan S1AP — thiết kế đó đã đúng, không cần sửa gì.

#### Data plane thật sự đi đâu

```
UE ──(Uu radio)── eNB ──(S1-U, GTP-U)── SGW-U ──(S5-U, GTP-U)── PGW-U ──(SGi)── Internet/PDN
```

`epc-up-linux` (sgwu/pgwu của bạn) chỉ tham gia vào **S1-U và S5-U/SGi** — hoàn toàn không liên quan tới radio (Uu) hay S1-MME. eNB tự **đóng gói/mở gói GTP-U** phía radio, còn SGW-U/PGW-U của bạn chỉ relay GTP-U tunnel theo F-TEID đã được PFCP lập trình.

---

### Phần 2: Kiến trúc `epc-up-linux`

Đây là phần quan trọng nhất còn thiếu trong hệ thống của bạn (❌ placeholder hiện tại). Có 3 hướng tiếp cận, khác nhau về độ phức tạp/hiệu năng:

#### Phương án A: Kernel GTP module (`gtp5g` / kernel `gtp`)

```
                    PFCP (Sxa/Sxb)
epc-sgwc/pgwc ─────────────────────► LinuxGtpAgent
                                          │ netlink (ip / rtnetlink)
                                          ▼
                                    Linux kernel `gtp` module
                                    (tạo interface gtp0, PDP context)
                                          │
                        ┌─────────────────┼─────────────────┐
                        ▼                                    ▼
                   encapsulate/decapsulate            route qua kernel netfilter
                   GTP-U packet (UDP:2152)             (SGi NAT nếu cần)
```

**Cách hoạt động:**

- Kernel Linux có module `gtp` (từ kernel 4.7+, cần load `modprobe gtp`) hoặc dùng driver mở rộng `gtp5g` (dự án OpenAirInterface làm cho 5G nhưng dùng được tương tự cho 4G) — tạo ra **virtual network interface** kiểu GTP tunnel.
- `LinuxGtpAgent` của bạn nhận lệnh PFCP (PDR/FAR/QER/URR) từ `epc-sgwc`/`epc-pgwc`, dịch thành **netlink command** (`ip link add gtp0 type gtp ...`, thêm PDP context qua `genl`/`libgtpnl`) để kernel tự động encapsulate/decapsulate GTP-U packet **trong kernel space** — không qua userspace nữa sau khi đã setup xong tunnel.

**Ưu điểm:**

- Hiệu năng cao — packet processing nằm hẳn trong kernel, không có context switch userspace↔kernel mỗi packet (khác hẳn TUN interface mà Open5GS UPF built-in dùng — đây chính là điểm yếu Open5GS UPF đã bàn ở câu trước, TUN yêu cầu copy packet qua userspace).
- Tận dụng được kernel netfilter/iptables cho NAT SGi, QoS shaping (tc/htb) nếu cần enforce theo QER.

**Nhược điểm:**

- Cần quyền `CAP_NET_ADMIN` / chạy container privileged, hoặc cấp `NET_ADMIN` capability cụ thể.
- Phụ thuộc kernel version của host — không phải mọi distro/kernel đều có sẵn module `gtp`, cần build/load thủ công, khó portable giữa các môi trường (dev laptop macOS/Windows sẽ không chạy được, phải test trên Linux thật hoặc VM).
- QER (QoS Enforcement Rule) — rate limiting theo MBR/GBR — không có sẵn API trực tiếp trong kernel `gtp` module, phải tự ghép thêm `tc`/`htb` bên cạnh, tăng độ phức tạp.

#### Phương án B: Userspace packet processing (DPDK/eBPF-XDP)

```
epc-sgwc/pgwc ──PFCP──► LinuxGtpAgent ──► eBPF/XDP program hoặc DPDK poll-mode driver
                                              │
                                    Xử lý GTP-U packet trực tiếp ở NIC driver level
                                    (bypass kernel network stack hoàn toàn)
```

**Ưu điểm:**

- Throughput cao nhất trong 3 phương án (đây chính là hướng mà `eUPF` — nhắc tới ở câu so sánh trước — sử dụng, dựa trên eBPF/XDP).
- QER dễ implement hơn vì bạn tự kiểm soát toàn bộ vòng đời packet trong chương trình.

**Nhược điểm:**

- Độ phức tạp triển khai **cao nhất** — DPDK cần NIC hỗ trợ, hugepages, CPU pinning; eBPF/XDP cần kernel đủ mới + kiến thức lập trình eBPF (C, verifier constraints khó debug).
- Không phù hợp cho giai đoạn hiện tại của bạn khi còn đang ở Phase 1 (chưa có cả wire attach cơ bản) — đây là tối ưu hoá quá sớm.

#### Phương án C: Userspace socket + TUN interface (đơn giản nhất, giống Open5GS mặc định)

```
epc-sgwc/pgwc ──PFCP──► LinuxGtpAgent (Java/JVM process hoặc sidecar)
                              │
                    raw UDP socket :2152 (nhận GTP-U)
                              │
                    tự parse GTP header, strip/add, ghi vào TUN interface
                              │
                         TUN (ogstun-like)
                              │
                    Linux routing table → SGi/internet
```

**Ưu điểm:**

- Đơn giản nhất để implement và debug, không cần kernel module đặc biệt, chạy được trên bất kỳ Linux nào (kể cả container non-privileged nếu chỉ cần tạo TUN với `CAP_NET_ADMIN` tối thiểu).
- Portable, dễ test trên CI/CD, dễ chạy trong Docker Compose lab như hệ thống hiện tại của bạn.

**Nhược điểm:**

- Chính là điểm yếu mà Open5GS UPF built-in gặp phải (đã phân tích ở câu trước) — throughput bị giới hạn bởi việc copy packet qua userspace 2 lần (kernel→userspace lúc nhận UDP, userspace→kernel lúc ghi TUN), CPU-bound theo single-core clock speed.

#### Khuyến nghị cho giai đoạn hiện tại của bạn

Vì bạn đang ở **Phase 1** (thậm chí còn chưa có wire attach cơ bản, `epc-up-linux` mới có `LinuxGtpAgent` khung sườn), mình khuyến nghị:

**Bắt đầu với Phương án C (userspace TUN)** để **đóng Phase 1-3 nhanh nhất** (wire attach + PCEF/charging + acceptance test cơ bản), vì:

1. Đây là phương án ít rủi ro nhất, không phụ thuộc kernel module đặc biệt — bạn cần chứng minh **toàn bộ signaling chain hoạt động đúng** trước (S6a→S11→S5→PFCP→data path tồn tại), chưa cần tối ưu throughput vội.
2. Việc bạn dự định "epc-up-linux **agent gọi kernel** `gtp`**/**`ip` **command**" đã cho thấy ý định đi hướng A — vậy có thể coi **Phương án C là bước đệm/fallback**, còn Phương án A là target thật.

**Sau đó nâng cấp lên Phương án A (kernel gtp module)** ở Phase 4-5 khi bạn cần đo **"Gigabit / 10k contexts"** như đã ghi trong gap checklist — vì mục tiêu throughput cao chỉ Phương án A hoặc B mới đáp ứng nổi, còn Phương án C sẽ hit trần CPU giống Open5GS UPF (đã thấy rõ trong benchmark thảo luận trước: chỉ 8 UE đồng thời đã ăn 93.75% CPU với kiểu TUN-based dataplane).

**Không nên nhảy thẳng lên Phương án B (DPDK/eBPF)** trừ khi sau khi đo thực tế Phương án A vẫn không đạt target throughput — đây là tối ưu hoá quá sớm (premature optimization) so với vị trí hiện tại của dự án (chưa qua G3 wire attach).

#### Điểm kỹ thuật cần làm rõ khi implement PDR/FAR/QER/URR trên `LinuxGtpAgent`

Dù chọn phương án nào, PFCP session establishment cần map đúng 4 loại rule sang hành động thật trên Linux:


| PFCP Rule                                                                                      | Hành động Linux tương ứng                                                                                                                                                               |
| ---------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **PDR** (Packet Detection Rule) — nhận diện traffic theo F-TEID/UE IP                          | Với kernel `gtp`: map vào PDP context lookup table. Với TUN: parse GTP header, match TEID trong bảng session của bạn                                                                    |
| **FAR** (Forwarding Action Rule) — forward đi đâu (encapsulate GTP hay bỏ vào TUN đi Internet) | Với kernel `gtp`: kernel tự route theo interface. Với TUN: bạn tự quyết định ghi ra TUN (uplink→internet) hay đóng gói GTP gửi tiếp (downlink→SGW/eNB)                                  |
| **QER** (QoS Enforcement Rule) — MBR/GBR rate limit                                            | Cần `tc`/`htb` filter theo TEID hoặc UE IP — **đây là phần khó nhất, không có sẵn trong kernel** `gtp` **module, phải tự ghép**                                                         |
| **URR** (Usage Reporting Rule) — đếm byte cho charging (Gy/CDR)                                | Cần đếm packet/byte per-session — kernel `gtp` không tự expose counter theo TEID ra userspace mặc định, thường phải tự polling qua conntrack hoặc tự đếm ở userspace agent nếu dùng TUN |


**Lưu ý quan trọng**: URR chính là cầu nối giữa `epc-up-linux` và `epc-pcef`/charging (Gy) đã bàn ở câu trước — nếu bạn chọn kernel `gtp` module thuần, cần thiết kế cơ chế polling counter riêng (VD: netlink dump định kỳ) để feed usage data về `epc-pgwc`→PCEF→CDR, vì đây không tự động có sẵn.

---

Bạn muốn mình đi sâu hơn vào phần nào — VD: cụ thể cách map PFCP FAR sang route table Linux (uplink/downlink forwarding logic chi tiết), hay thiết kế cơ chế polling URR counter cho charging?

cách map PFCP FAR sang route table Linux (uplink/downlink forwarding logic chi tiết), và thiết kế cơ chế polling URR counter cho charging?

Thought for 1m 9s

## 1. Map PFCP FAR sang Linux routing/forwarding — uplink &amp; downlink

### Nhắc lại ngữ nghĩa PFCP trước khi map

Mỗi **PDR** (Packet Detection Rule) mô tả "gói tin nào tới thì match rule này" (qua `Source Interface` + `F-TEID` hoặc `UE IP Address`), và trỏ tới một **FAR** mô tả "match rồi thì làm gì" (`Apply Action` = FORW/DROP/BUFF + `Outer Header Creation` nếu cần encapsulate GTP-U khi forward ra).

Vai trò của node quyết định luồng khác nhau:

- **PGW-U**: một bên là GTP tunnel (Access, hướng SGW-U), một bên là **plain IP** (SGi, hướng Internet) — đây là "điểm tháo/gắn GTP" cuối cùng.
- **SGW-U**: **cả hai bên đều là GTP tunnel** (S1-U hướng eNB, S5-U hướng PGW-U) — đây thuần tuý là **relay/translate TEID**, không có phía IP trần nào cả.

### PGW-U — dùng kernel `gtp` module (1 interface)

```
                    ┌─────────────── PGW-U (Linux) ───────────────┐
                    │                                              │
  S5-U (GTP-U) ─────┼──► gtp0 interface ◄────────────────────┐    │
  từ SGW-U           │    (decap/encap GTP-U tự động          │    │
                    │     theo PDP context)                   │    │
                    │         │                                │    │
                    │         │ plain IP (sau decap)           │    │
                    │         ▼                                │    │
                    │    kernel routing table                  │    │
                    │         │                                │    │
                    │         ▼                                │    │
                    │    eth0 (SGi) ── NAT (nếu cần) ──► Internet
                    └──────────────────────────────────────────┘
```

**Uplink (UE → Internet):**

1. Gói GTP-U tới `gtp0` qua UDP:2152, kernel tra PDP context theo **local TEID** → tìm `ms_addr` (UE IP) tương ứng → **decapsulate** (= áp dụng `Outer Header Removal` trong PDR).
2. Gói IP trần (source = UE IP) được kernel coi như **nhận nguyên sinh trên** `gtp0` → đi qua routing table bình thường → ra `eth0` (SGi) → NAT nếu APN cấu hình private IP.
3. Đây chính là hành động FAR: `Apply Action=FORW`, `Destination Interface=SGi`, **không có** `Outer Header Creation` (vì ra ngoài là IP trần).

**Downlink (Internet → UE):**

1. Gói tới `eth0` với destination = UE IP → cần route để kernel **đẩy vào** `gtp0` thay vì drop: `ip route add <UE-IP>/32 dev gtp0`.
2. `gtp0` tra PDP context theo `ms_addr` = UE IP → tìm được **remote TEID + peer IP (SGW-U)** đã cấu hình khi PFCP Session Establishment → **encapsulate** GTP-U rồi gửi ra.
3. Đây là FAR: `Apply Action=FORW`, `Destination Interface=Access`, **có** `Outer Header Creation` (TEID+IP của SGW-U lấy từ IE `F-TEID` trong PFCP Session Establishment Request phía CP gửi xuống).

**Lệnh netlink cần** `LinuxGtpAgent` **phát ra khi nhận PFCP Session Establishment/Modification (dùng** `libgtpnl` **hoặc raw genl socket):**

bash

```bash
# Tạo interface (1 lần khi start, không phải mỗi session)
ip link add gtp0 type gtp role pgw

# Mỗi session mới (từ PDR+FAR pair):
gtp-tunnel add gtp0 v1 <local-TEID> <remote-TEID> <UE-IP> <SGW-U-peer-IP>

# Route để downlink packet từ SGi đi vào gtp0
ip route add <UE-IP>/32 dev gtp0
```

### SGW-U — dùng **2 interface** `gtp` **bridge với nhau** (không có phía IP trần)

Đây là phần dễ bị làm sai nhất vì SGW-U không có "SGi" nào — cần setup **2 gtp interface**, mỗi cái đại diện 1 phía tunnel, rồi **route UE IP giữa 2 interface** để relay.

```
                         ┌─────────────── SGW-U (Linux) ───────────────┐
                         │                                              │
  S1-U (GTP-U) ──────────┼──► gtp-access ◄──┐         ┌──► gtp-core ◄───┼────── S5-U (GTP-U)
  từ eNB                  │   (TEID phía eNB) │         │  (TEID phía   │        tới PGW-U
                         │                    │         │   PGW-U)      │
                         │                    └── route <UE-IP>/32 ─────┘
                         │                        (bridge 2 tunnel qua UE IP)
                         └──────────────────────────────────────────────┘
```

**Uplink (eNB → PGW-U):**

1. Gói GTP-U tới `gtp-access` (TEID phía eNB) → decapsulate → lộ ra IP trần (source = UE IP).
2. Route nội bộ: `ip route add <UE-IP>/32 dev gtp-core` — đẩy gói này (đang "trần" tạm thời trong kernel) sang phía `gtp-core`.
3. `gtp-core` tra PDP context theo `ms_addr` → encapsulate lại với TEID/IP của **PGW-U** → gửi ra S5-U.

**Downlink**: ngược lại hoàn toàn — `gtp-core` decap gói từ PGW-U, route `<UE-IP>/32 dev gtp-access` đẩy sang `gtp-access`, encap lại với TEID/IP của **eNB** rồi gửi ra S1-U.

bash

```bash
# Setup 2 interface
ip link add gtp-access type gtp role sgsn   # phía eNB
ip link add gtp-core   type gtp role sgsn   # phía PGW-U

# PDP context mỗi phía (2 lệnh riêng, mỗi session)
gtp-tunnel add gtp-access v1 <TEID-local-access> <TEID-eNB> <UE-IP> <eNB-IP>
gtp-tunnel add gtp-core   v1 <TEID-local-core>   <TEID-PGWU> <UE-IP> <PGW-U-IP>

# Route bridge 2 chiều
ip route add <UE-IP>/32 dev gtp-core    # cho uplink relay
ip route add <UE-IP>/32 dev gtp-access  # cho downlink relay
```

> ⚠️ Lưu ý: một địa chỉ đích (`<UE-IP>/32`) chỉ route được **1 device** tại một thời điểm trong bảng route thông thường — thực tế bạn cần dùng **policy routing** (`ip rule` + nhiều route table riêng theo hướng packet, phân biệt bằng `fwmark` gắn lúc packet vừa ra khỏi `gtp-access`/`gtp-core`) chứ không thể add 2 route trùng destination vào cùng 1 table. Cách làm chuẩn: dùng `iptables -t mangle` gắn mark khi gói tới từ `gtp-access` → route theo table riêng trỏ `gtp-core`, và ngược lại.

### Ánh xạ FAR field ↔ hành động Linux (bảng tổng hợp)


| PFCP FAR field                                                     | PGW-U                                                                                                                                                                                                       | SGW-U                    |
| ------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------ |
| `Apply Action = DROP`                                              | Không add PDP context / add rule drop                                                                                                                                                                       | Tương tự                 |
| `Apply Action = FORW`, `Dest = Access`, có `Outer Header Creation` | `gtp0` encap → SGW-U                                                                                                                                                                                        | `gtp-access` encap → eNB |
| `Apply Action = FORW`, `Dest = Core`, có `Outer Header Creation`   | — (PGW-U không có Core hướng ra)                                                                                                                                                                            | `gtp-core` encap → PGW-U |
| `Apply Action = FORW`, `Dest = SGi`, không `Outer Header Creation` | Route bình thường ra `eth0` + NAT                                                                                                                                                                           | — (SGW-U không có SGi)   |
| `Apply Action = BUFF`                                              | Buffer packet (downlink data notification khi UE idle) — **cần userspace queue, kernel** `gtp` **không tự hỗ trợ**, phải giữ packet trong `LinuxGtpAgent` cho tới khi nhận lệnh `Update FAR` release buffer | Tương tự                 |


**Điểm khó nhất cần lưu ý**: `Apply Action=BUFF` (buffering khi UE ở idle, chờ Paging thành công) **kernel** `gtp` **module không hỗ trợ sẵn** — bạn phải tự implement buffer queue ở tầng `LinuxGtpAgent`/userspace (giữ gói lại, không tạo PDP context tunnel cho tới khi PFCP Session Modification báo UE đã reachable trở lại).

---

## 2. Thiết kế cơ chế polling URR counter cho charging

### Vấn đề đầu tiên: kernel `gtp` module không có counter per-session sẵn dùng được

`ip -s link show gtp0` chỉ cho counter **tổng interface**, không tách theo từng UE/TEID — không đủ cho URR (mỗi session cần đếm riêng để báo về `epc-pgwc`→PCEF→Gy). Cần một lớp đếm riêng, **không dựa vào counter mặc định của kernel gtp**.

### Lựa chọn nơi đếm — trade-off theo quy mô


| Cách đếm                                                                  | Ưu                                                                                 | Nhược                                                                                                                                                                      | Phù hợp giai đoạn                                                     |
| ------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| **iptables rule per-UE** (1 rule/UE, đọc counter qua `iptables -L -v -x`) | Dễ làm nhất                                                                        | **O(n) linear scan mỗi packet** khi có hàng nghìn rule — chính là loại nghẽn hiệu năng bạn đã gặp phải với vấn đề throughput UPF ở câu trước, càng nhiều session càng chậm | Chỉ nên dùng cho lab/demo, **không dùng khi hướng tới 10k CCU**       |
| **nftables named set/map với per-element counter**                        | O(1) lookup dù bao nhiêu session (hash-based), scale tốt tới hàng chục nghìn entry | Cần nftables (khá mới, cần kernel/iptables-nft đủ version), API JSON để đọc counter                                                                                        | **Khuyến nghị cho Phase 1-4** — cân bằng độ phức tạp/hiệu năng        |
| **eBPF map (gắn trên** `tc`**/XDP của interface gtp)**                    | Hiệu năng cao nhất, tuỳ biến sâu (đếm theo TEID chứ không chỉ IP)                  | Cần kiến thức eBPF, khó debug, đúng như đã cảnh báo ở Phương án B trước — tối ưu hoá sớm                                                                                   | Chỉ nên làm khi đã đo thấy nftables không đủ, để dành cho sau Phase 5 |


**Khuyến nghị**: dùng **nftables set với counter per-element**, khớp với timeline hiện tại của bạn:

bash

```bash
# Setup 1 lần
nft add table inet epc_urr
nft add set inet epc_urr ue_sessions { type ipv4_addr; flags interval; counter; }

# Mỗi session mới (khi PFCP tạo URR gắn với PDR)
nft add element inet epc_urr ue_sessions { <UE-IP> counter }

# Đọc counter (LinuxGtpAgent poll định kỳ)
nft -j list set inet epc_urr ue_sessions
```

Output JSON trả về `packets`/`bytes` cho từng element — đọc theo chu kỳ polling.

### Kiến trúc polling agent

```
┌──────────────────────────── LinuxGtpAgent ────────────────────────────┐
│                                                                         │
│  URR Registry: Map<urr_id, {                                          │
│      linked_pdr_ids, measurement_method,                              │
│      volume_threshold, time_threshold,                                │
│      reporting_triggers (PERIO/VOLTH/TIMTH/...),                      │
│      last_reported_baseline (uplink_bytes, downlink_bytes)            │
│  }>                                                                    │
│                                                                         │
│  ┌─────────────────┐        ┌───────────────────────────────┐        │
│  │  Poller Thread   │──────► │ nft list set (per chu kỳ)      │        │
│  │  (interval = min │        │ hoặc bpf_map_lookup_elem       │        │
│  │   config'd URR   │        └───────────────────────────────┘        │
│  │   period)        │                    │                            │
│  └─────────────────┘                    ▼                             │
│           │                    delta = current - baseline             │
│           │                                │                          │
│           ▼                                ▼                          │
│   ┌───────────────┐         ┌─────────────────────────────┐         │
│   │ Time threshold │         │ Volume threshold check       │         │
│   │ elapsed?       │         │ delta >= Volume-Threshold?   │         │
│   └───────┬────────┘         └──────────────┬────────────────┘        │
│           │  yes                             │  yes                   │
│           └────────────┬────────────────────┘                        │
│                         ▼                                             │
│           PFCP Session Report Request                                 │
│           (Usage Report IE: URR-ID, Volume-Measurement,               │
│            Usage-Report-Trigger=PERIO/VOLTH, Duration-Measurement)    │
│                         │                                             │
│                         ▼                                             │
│              gửi Sxa/Sxb tới epc-sgwc/epc-pgwc                        │
│                         │                                             │
│           cập nhật baseline = current (reset cho chu kỳ tiếp)         │
└──────────────────────────────────────────────────────────────────────┘
                          │
                          ▼
              PgwControl → PcefService.reportUsage()
                          │
                          ▼
              PcrfClient (Gx) hoặc trực tiếp Gy → OCS
              (đối chiếu với quota đã cấp — Granted-Service-Unit)
```

### Điểm thiết kế quan trọng cần quyết định rõ

**1. Delta hay cumulative?** PFCP Volume-Measurement trong Usage Report thường là **usage kể từ lần report trước** (không phải tổng từ đầu session), nên `LinuxGtpAgent` phải tự giữ `last_reported_baseline` và tính delta mỗi lần gửi — nếu quên reset baseline sau mỗi report, sẽ **double-count** ở phía `PcefService`/OCS (chính là rủi ro "double-debit" đã cảnh báo ở câu trước về charging reconcile).

**2. Chu kỳ polling — cân bằng giữa overhead và rủi ro vượt quota:**

- Polling quá dày (VD: mỗi 100ms) → tốn CPU đọc counter cho hàng nghìn session liên tục, đặc biệt nếu dùng iptables (linear scan).
- Polling quá thưa (VD: mỗi 60s) → rủi ro thật: nếu OCS chỉ cấp quota giới hạn (Gy `Granted-Service-Unit`) và user tiêu hết quota **trước khi** agent kịp polling+report+nhận refill, user sẽ **overrun quota mà không bị chặn kịp** (leak doanh thu). Nên set `Volume-Threshold` trong URR **thấp hơn quota cấp một khoảng an toàn** (VD: báo khi dùng 80% quota) để có buffer thời gian round-trip report→refill trước khi cạn hẳn.

**3. Mất counter khi agent restart** — vì kernel `gtp`/nftables counter **sống trong kernel state**, nếu `LinuxGtpAgent` (userspace) crash và restart, counter kernel vẫn còn nguyên (không mất, vì nằm trong kernel không phải trong process) — nhưng **baseline trong bộ nhớ JVM của agent bị mất**, dẫn tới lần poll đầu sau restart sẽ tính delta sai (tưởng tất cả traffic từ đầu session là "mới"). Cần **persist baseline** (giống cơ chế `DualWriteSessionStore` bạn đã có cho session checkpoint — nên áp dụng pattern tương tự cho URR baseline, ghi xuống Postgres/Infinispan định kỳ không phải mỗi lần poll để tránh hot-path chậm).

**4. Immediate/Query report** — khi PFCP CP-function gửi `PFCP Session Modification Request` kèm `Query URR` (VD: session sắp đóng, hoặc PCRF cần biết usage ngay để tính lại policy), `LinuxGtpAgent` phải hỗ trợ đọc counter **on-demand** ngoài chu kỳ polling định kỳ — nên tách hàm đọc counter (`readCounter(urr_id)`) độc lập khỏi vòng lặp Poller Thread để dùng lại được cho cả 2 trường hợp.

---

Bạn muốn mình đi tiếp vào phần nào — thiết kế chi tiết cơ chế **policy routing (**`ip rule`**/**`fwmark`**)** để bridge 2 gtp interface ở SGW-U cho đúng (phần mình đã cảnh báo ở trên nhưng chưa detail), hay thiết kế **QER (rate limiting MBR/GBR)** ghép với `tc`?