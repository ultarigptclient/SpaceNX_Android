@file:Suppress("FunctionName")

package net.spacenx.messenger.data.remote.socket.codec

/**
 * SEED 블록 암호 알고리즘 (KISA 표준)
 * - 128bit 블록, 128bit 키, 16라운드 Feistel 구조
 * - 원본: SEED_KISA.java (2006)
 */
object SeedCipher {

    // @formatter:off
    private val SS0 = intArrayOf(
        0x2989a1a8.toInt(), 0x05858184, 0x16c6d2d4, 0x13c3d3d0.toInt(), 0x14445054, 0x1d0d111c, 0x2c8ca0ac.toInt(), 0x25052124,
        0x1d4d515c, 0x03434340, 0x18081018, 0x1e0e121c, 0x11415150, 0x3cccf0fc.toInt(), 0x0acac2c8.toInt(), 0x23436360,
        0x28082028, 0x04444044, 0x20002020, 0x1d8d919c, 0x20c0e0e0.toInt(), 0x22c2e2e0.toInt(), 0x08c8c0c8.toInt(), 0x17071314,
        0x2585a1a4.toInt(), 0x0f8f838c.toInt(), 0x03030300, 0x3b4b7378, 0x3b8bb3b8.toInt(), 0x13031310, 0x12c2d2d0.toInt(), 0x2ecee2ec.toInt(),
        0x30407070, 0x0c8c808c.toInt(), 0x3f0f333c, 0x2888a0a8.toInt(), 0x32023230, 0x1dcdd1dc.toInt(), 0x36c6f2f4.toInt(), 0x34447074,
        0x2ccce0ec.toInt(), 0x15859194.toInt(), 0x0b0b0308, 0x17475354, 0x1c4c505c, 0x1b4b5358, 0x3d8db1bc.toInt(), 0x01010100,
        0x24042024, 0x1c0c101c, 0x33437370, 0x18889098.toInt(), 0x10001010, 0x0cccc0cc.toInt(), 0x32c2f2f0.toInt(), 0x19c9d1d8.toInt(),
        0x2c0c202c, 0x27c7e3e4.toInt(), 0x32427270, 0x03838380.toInt(), 0x1b8b9398.toInt(), 0x11c1d1d0.toInt(), 0x06868284.toInt(), 0x09c9c1c8.toInt(),
        0x20406060, 0x10405050, 0x2383a3a0.toInt(), 0x2bcbe3e8.toInt(), 0x0d0d010c, 0x3686b2b4.toInt(), 0x1e8e929c.toInt(), 0x0f4f434c,
        0x3787b3b4.toInt(), 0x1a4a5258, 0x06c6c2c4.toInt(), 0x38487078, 0x2686a2a4.toInt(), 0x12021210, 0x2f8fa3ac.toInt(), 0x15c5d1d4.toInt(),
        0x21416160, 0x03c3c3c0.toInt(), 0x3484b0b4.toInt(), 0x01414140, 0x12425250, 0x3d4d717c, 0x0d8d818c.toInt(), 0x08080008,
        0x1f0f131c, 0x19899198.toInt(), 0x00000000, 0x19091118, 0x04040004, 0x13435350, 0x37c7f3f4.toInt(), 0x21c1e1e0.toInt(),
        0x3dcdf1fc.toInt(), 0x36467274, 0x2f0f232c, 0x27072324, 0x3080b0b0.toInt(), 0x0b8b8388.toInt(), 0x0e0e020c, 0x2b8ba3a8.toInt(),
        0x2282a2a0.toInt(), 0x2e4e626c, 0x13839390.toInt(), 0x0d4d414c, 0x29496168, 0x3c4c707c, 0x09090108, 0x0a0a0208,
        0x3f8fb3bc.toInt(), 0x2fcfe3ec.toInt(), 0x33c3f3f0.toInt(), 0x05c5c1c4.toInt(), 0x07878384.toInt(), 0x14041014, 0x3ecef2fc.toInt(), 0x24446064,
        0x1eced2dc.toInt(), 0x2e0e222c, 0x0b4b4348, 0x1a0a1218, 0x06060204, 0x21012120, 0x2b4b6368, 0x26466264,
        0x02020200, 0x35c5f1f4.toInt(), 0x12829290.toInt(), 0x0a8a8288.toInt(), 0x0c0c000c, 0x3383b3b0.toInt(), 0x3e4e727c, 0x10c0d0d0.toInt(),
        0x3a4a7278, 0x07474344, 0x16869294.toInt(), 0x25c5e1e4.toInt(), 0x26062224, 0x00808080.toInt(), 0x2d8da1ac.toInt(), 0x1fcfd3dc.toInt(),
        0x2181a1a0.toInt(), 0x30003030, 0x37073334, 0x2e8ea2ac.toInt(), 0x36063234, 0x15051114, 0x22022220, 0x38083038,
        0x34c4f0f4.toInt(), 0x2787a3a4.toInt(), 0x05454144, 0x0c4c404c, 0x01818180.toInt(), 0x29c9e1e8.toInt(), 0x04848084.toInt(), 0x17879394.toInt(),
        0x35053134, 0x0bcbc3c8.toInt(), 0x0ecec2cc.toInt(), 0x3c0c303c, 0x31417170, 0x11011110, 0x07c7c3c4.toInt(), 0x09898188.toInt(),
        0x35457174, 0x3bcbf3f8.toInt(), 0x1acad2d8.toInt(), 0x38c8f0f8.toInt(), 0x14849094.toInt(), 0x19495158, 0x02828280.toInt(), 0x04c4c0c4.toInt(),
        0x3fcff3fc.toInt(), 0x09494148, 0x39093138, 0x27476364, 0x00c0c0c0.toInt(), 0x0fcfc3cc.toInt(), 0x17c7d3d4.toInt(), 0x3888b0b8.toInt(),
        0x0f0f030c, 0x0e8e828c.toInt(), 0x02424240, 0x23032320, 0x11819190.toInt(), 0x2c4c606c, 0x1bcbd3d8.toInt(), 0x2484a0a4.toInt(),
        0x34043034, 0x31c1f1f0.toInt(), 0x08484048, 0x02c2c2c0.toInt(), 0x2f4f636c, 0x3d0d313c, 0x2d0d212c, 0x00404040,
        0x3e8eb2bc.toInt(), 0x3e0e323c, 0x3c8cb0bc.toInt(), 0x01c1c1c0.toInt(), 0x2a8aa2a8.toInt(), 0x3a8ab2b8.toInt(), 0x0e4e424c, 0x15455154,
        0x3b0b3338, 0x1cccd0dc.toInt(), 0x28486068, 0x3f4f737c, 0x1c8c909c.toInt(), 0x18c8d0d8.toInt(), 0x0a4a4248, 0x16465254,
        0x37477374, 0x2080a0a0.toInt(), 0x2dcde1ec.toInt(), 0x06464244, 0x3585b1b4.toInt(), 0x2b0b2328, 0x25456164, 0x3acaf2f8.toInt(),
        0x23c3e3e0.toInt(), 0x3989b1b8.toInt(), 0x3181b1b0.toInt(), 0x1f8f939c.toInt(), 0x1e4e525c, 0x39c9f1f8.toInt(), 0x26c6e2e4.toInt(), 0x3282b2b0.toInt(),
        0x31013130, 0x2acae2e8.toInt(), 0x2d4d616c, 0x1f4f535c, 0x24c4e0e4.toInt(), 0x30c0f0f0.toInt(), 0x0dcdc1cc.toInt(), 0x08888088.toInt(),
        0x16061214, 0x3a0a3238, 0x18485058, 0x14c4d0d4.toInt(), 0x22426260, 0x29092128, 0x07070304, 0x33033330,
        0x28c8e0e8.toInt(), 0x1b0b1318, 0x05050104, 0x39497178, 0x10809090.toInt(), 0x2a4a6268, 0x2a0a2228, 0x1a8a9298.toInt()
    )

    private val SS1 = intArrayOf(
        0x38380830, 0xe828c8e0.toInt(), 0x2c2d0d21, 0xa42686a2.toInt(), 0xcc0fcfc3.toInt(), 0xdc1eced2.toInt(), 0xb03383b3.toInt(), 0xb83888b0.toInt(),
        0xac2f8fa3.toInt(), 0x60204060, 0x54154551, 0xc407c7c3.toInt(), 0x44044440, 0x6c2f4f63, 0x682b4b63, 0x581b4b53,
        0xc003c3c3.toInt(), 0x60224262, 0x30330333, 0xb43585b1.toInt(), 0x28290921, 0xa02080a0.toInt(), 0xe022c2e2.toInt(), 0xa42787a3.toInt(),
        0xd013c3d3.toInt(), 0x90118191.toInt(), 0x10110111, 0x04060602, 0x1c1c0c10, 0xbc3c8cb0.toInt(), 0x34360632, 0x480b4b43,
        0xec2fcfe3.toInt(), 0x88088880.toInt(), 0x6c2c4c60, 0xa82888a0.toInt(), 0x14170713, 0xc404c4c0.toInt(), 0x14160612, 0xf434c4f0.toInt(),
        0xc002c2c2.toInt(), 0x44054541, 0xe021c1e1.toInt(), 0xd416c6d2.toInt(), 0x3c3f0f33, 0x3c3d0d31, 0x8c0e8e82.toInt(), 0x98188890.toInt(),
        0x28280820, 0x4c0e4e42, 0xf436c6f2.toInt(), 0x3c3e0e32, 0xa42585a1.toInt(), 0xf839c9f1.toInt(), 0x0c0d0d01, 0xdc1fcfd3.toInt(),
        0xd818c8d0.toInt(), 0x282b0b23, 0x64264662, 0x783a4a72, 0x24270723, 0x2c2f0f23, 0xf031c1f1.toInt(), 0x70324272,
        0x40024242, 0xd414c4d0.toInt(), 0x40014141, 0xc000c0c0.toInt(), 0x70334373, 0x64274763, 0xac2c8ca0.toInt(), 0x880b8b83.toInt(),
        0xf437c7f3.toInt(), 0xac2d8da1.toInt(), 0x80008080.toInt(), 0x1c1f0f13, 0xc80acac2.toInt(), 0x2c2c0c20, 0xa82a8aa2.toInt(), 0x34340430,
        0xd012c2d2.toInt(), 0x080b0b03, 0xec2ecee2.toInt(), 0xe829c9e1.toInt(), 0x5c1d4d51, 0x94148490.toInt(), 0x18180810, 0xf838c8f0.toInt(),
        0x54174753, 0xac2e8ea2.toInt(), 0x08080800, 0xc405c5c1.toInt(), 0x10130313, 0xcc0dcdc1.toInt(), 0x84068682.toInt(), 0xb83989b1.toInt(),
        0xfc3fcff3.toInt(), 0x7c3d4d71, 0xc001c1c1.toInt(), 0x30310131, 0xf435c5f1.toInt(), 0x880a8a82.toInt(), 0x682a4a62, 0xb03181b1.toInt(),
        0xd011c1d1.toInt(), 0x20200020, 0xd417c7d3.toInt(), 0x00020202, 0x20220222, 0x04040400, 0x68284860, 0x70314171,
        0x04070703, 0xd81bcbd3.toInt(), 0x9c1d8d91.toInt(), 0x98198991.toInt(), 0x60214161, 0xbc3e8eb2.toInt(), 0xe426c6e2.toInt(), 0x58194951,
        0xdc1dcdd1.toInt(), 0x50114151, 0x90108090.toInt(), 0xdc1cccd0.toInt(), 0x981a8a92.toInt(), 0xa02383a3.toInt(), 0xa82b8ba3.toInt(), 0xd010c0d0.toInt(),
        0x80018181.toInt(), 0x0c0f0f03, 0x44074743, 0x181a0a12, 0xe023c3e3.toInt(), 0xec2ccce0.toInt(), 0x8c0d8d81.toInt(), 0xbc3f8fb3.toInt(),
        0x94168692.toInt(), 0x783b4b73, 0x5c1c4c50, 0xa02282a2.toInt(), 0xa02181a1.toInt(), 0x60234363, 0x20230323, 0x4c0d4d41,
        0xc808c8c0.toInt(), 0x9c1e8e92.toInt(), 0x9c1c8c90.toInt(), 0x383a0a32, 0x0c0c0c00, 0x2c2e0e22, 0xb83a8ab2.toInt(), 0x6c2e4e62,
        0x9c1f8f93.toInt(), 0x581a4a52, 0xf032c2f2.toInt(), 0x90128292.toInt(), 0xf033c3f3.toInt(), 0x48094941, 0x78384870, 0xcc0cccc0.toInt(),
        0x14150511, 0xf83bcbf3.toInt(), 0x70304070, 0x74354571, 0x7c3f4f73, 0x34350531, 0x10100010, 0x00030303,
        0x64244460, 0x6c2d4d61, 0xc406c6c2.toInt(), 0x74344470, 0xd415c5d1.toInt(), 0xb43484b0.toInt(), 0xe82acae2.toInt(), 0x08090901,
        0x74364672, 0x18190911, 0xfc3ecef2.toInt(), 0x40004040, 0x10120212, 0xe020c0e0.toInt(), 0xbc3d8db1.toInt(), 0x04050501,
        0xf83acaf2.toInt(), 0x00010101, 0xf030c0f0.toInt(), 0x282a0a22, 0x5c1e4e52, 0xa82989a1.toInt(), 0x54164652, 0x40034343,
        0x84058581.toInt(), 0x14140410, 0x88098981.toInt(), 0x981b8b93.toInt(), 0xb03080b0.toInt(), 0xe425c5e1.toInt(), 0x48084840, 0x78394971,
        0x94178793.toInt(), 0xfc3cccf0.toInt(), 0x1c1e0e12, 0x80028282.toInt(), 0x20210121, 0x8c0c8c80.toInt(), 0x181b0b13, 0x5c1f4f53,
        0x74374773, 0x54144450, 0xb03282b2.toInt(), 0x1c1d0d11, 0x24250521, 0x4c0f4f43, 0x00000000, 0x44064642,
        0xec2dcde1.toInt(), 0x58184850, 0x50124252, 0xe82bcbe3.toInt(), 0x7c3e4e72, 0xd81acad2.toInt(), 0xc809c9c1.toInt(), 0xfc3dcdf1.toInt(),
        0x30300030, 0x94158591.toInt(), 0x64254561, 0x3c3c0c30, 0xb43686b2.toInt(), 0xe424c4e0.toInt(), 0xb83b8bb3.toInt(), 0x7c3c4c70,
        0x0c0e0e02, 0x50104050, 0x38390931, 0x24260622, 0x30320232, 0x84048480.toInt(), 0x68294961, 0x90138393.toInt(),
        0x34370733, 0xe427c7e3.toInt(), 0x24240420, 0xa42484a0.toInt(), 0xc80bcbc3.toInt(), 0x50134353, 0x080a0a02, 0x84078783.toInt(),
        0xd819c9d1.toInt(), 0x4c0c4c40, 0x80038383.toInt(), 0x8c0f8f83.toInt(), 0xcc0ecec2.toInt(), 0x383b0b33, 0x480a4a42, 0xb43787b3.toInt()
    )

    private val SS2 = intArrayOf(
        0xa1a82989.toInt(), 0x81840585.toInt(), 0xd2d416c6.toInt(), 0xd3d013c3.toInt(), 0x50541444, 0x111c1d0d, 0xa0ac2c8c.toInt(), 0x21242505,
        0x515c1d4d, 0x43400343, 0x10181808, 0x121c1e0e, 0x51501141, 0xf0fc3ccc.toInt(), 0xc2c80aca.toInt(), 0x63602343,
        0x20282808, 0x40440444, 0x20202000, 0x919c1d8d.toInt(), 0xe0e020c0.toInt(), 0xe2e022c2.toInt(), 0xc0c808c8.toInt(), 0x13141707,
        0xa1a42585.toInt(), 0x838c0f8f.toInt(), 0x03000303, 0x73783b4b, 0xb3b83b8b.toInt(), 0x13101303, 0xd2d012c2.toInt(), 0xe2ec2ece.toInt(),
        0x70703040, 0x808c0c8c.toInt(), 0x333c3f0f, 0xa0a82888.toInt(), 0x32303202, 0xd1dc1dcd.toInt(), 0xf2f436c6.toInt(), 0x70743444,
        0xe0ec2ccc.toInt(), 0x91941585.toInt(), 0x03080b0b, 0x53541747, 0x505c1c4c, 0x53581b4b, 0xb1bc3d8d.toInt(), 0x01000101,
        0x20242404, 0x101c1c0c, 0x73703343, 0x90981888.toInt(), 0x10101000, 0xc0cc0ccc.toInt(), 0xf2f032c2.toInt(), 0xd1d819c9.toInt(),
        0x202c2c0c, 0xe3e427c7.toInt(), 0x72703242, 0x83800383.toInt(), 0x93981b8b.toInt(), 0xd1d011c1.toInt(), 0x82840686.toInt(), 0xc1c809c9.toInt(),
        0x60602040, 0x50501040, 0xa3a02383.toInt(), 0xe3e82bcb.toInt(), 0x010c0d0d, 0xb2b43686.toInt(), 0x929c1e8e.toInt(), 0x434c0f4f,
        0xb3b43787.toInt(), 0x52581a4a, 0xc2c406c6.toInt(), 0x70783848, 0xa2a42686.toInt(), 0x12101202, 0xa3ac2f8f.toInt(), 0xd1d415c5.toInt(),
        0x61602141, 0xc3c003c3.toInt(), 0xb0b43484.toInt(), 0x41400141, 0x52501242, 0x717c3d4d, 0x818c0d8d.toInt(), 0x00080808,
        0x131c1f0f, 0x91981989.toInt(), 0x00000000, 0x11181909, 0x00040404, 0x53501343, 0xf3f437c7.toInt(), 0xe1e021c1.toInt(),
        0xf1fc3dcd.toInt(), 0x72743646, 0x232c2f0f, 0x23242707, 0xb0b03080.toInt(), 0x83880b8b.toInt(), 0x020c0e0e, 0xa3a82b8b.toInt(),
        0xa2a02282.toInt(), 0x626c2e4e, 0x93901383.toInt(), 0x414c0d4d, 0x61682949, 0x707c3c4c, 0x01080909, 0x02080a0a,
        0xb3bc3f8f.toInt(), 0xe3ec2fcf.toInt(), 0xf3f033c3.toInt(), 0xc1c405c5.toInt(), 0x83840787.toInt(), 0x10141404, 0xf2fc3ece.toInt(), 0x60642444,
        0xd2dc1ece.toInt(), 0x222c2e0e, 0x43480b4b, 0x12181a0a, 0x02040606, 0x21202101, 0x63682b4b, 0x62642646,
        0x02000202, 0xf1f435c5.toInt(), 0x92901282.toInt(), 0x82880a8a.toInt(), 0x000c0c0c, 0xb3b03383.toInt(), 0x727c3e4e, 0xd0d010c0.toInt(),
        0x72783a4a, 0x43440747, 0x92941686.toInt(), 0xe1e425c5.toInt(), 0x22242606, 0x80800080.toInt(), 0xa1ac2d8d.toInt(), 0xd3dc1fcf.toInt(),
        0xa1a02181.toInt(), 0x30303000, 0x33343707, 0xa2ac2e8e.toInt(), 0x32343606, 0x11141505, 0x22202202, 0x30383808,
        0xf0f434c4.toInt(), 0xa3a42787.toInt(), 0x41440545, 0x404c0c4c, 0x81800181.toInt(), 0xe1e829c9.toInt(), 0x80840484.toInt(), 0x93941787.toInt(),
        0x31343505, 0xc3c80bcb.toInt(), 0xc2cc0ece.toInt(), 0x303c3c0c, 0x71703141, 0x11101101, 0xc3c407c7.toInt(), 0x81880989.toInt(),
        0x71743545, 0xf3f83bcb.toInt(), 0xd2d81aca.toInt(), 0xf0f838c8.toInt(), 0x90941484.toInt(), 0x51581949, 0x82800282.toInt(), 0xc0c404c4.toInt(),
        0xf3fc3fcf.toInt(), 0x41480949, 0x31383909, 0x63642747, 0xc0c000c0.toInt(), 0xc3cc0fcf.toInt(), 0xd3d417c7.toInt(), 0xb0b83888.toInt(),
        0x030c0f0f, 0x828c0e8e.toInt(), 0x42400242, 0x23202303, 0x91901181.toInt(), 0x606c2c4c, 0xd3d81bcb.toInt(), 0xa0a42484.toInt(),
        0x30343404, 0xf1f031c1.toInt(), 0x40480848, 0xc2c002c2.toInt(), 0x636c2f4f, 0x313c3d0d, 0x212c2d0d, 0x40400040,
        0xb2bc3e8e.toInt(), 0x323c3e0e, 0xb0bc3c8c.toInt(), 0xc1c001c1.toInt(), 0xa2a82a8a.toInt(), 0xb2b83a8a.toInt(), 0x424c0e4e, 0x51541545,
        0x33383b0b, 0xd0dc1ccc.toInt(), 0x60682848, 0x737c3f4f, 0x909c1c8c.toInt(), 0xd0d818c8.toInt(), 0x42480a4a, 0x52541646,
        0x73743747, 0xa0a02080.toInt(), 0xe1ec2dcd.toInt(), 0x42440646, 0xb1b43585.toInt(), 0x23282b0b, 0x61642545, 0xf2f83aca.toInt(),
        0xe3e023c3.toInt(), 0xb1b83989.toInt(), 0xb1b03181.toInt(), 0x939c1f8f.toInt(), 0x525c1e4e, 0xf1f839c9.toInt(), 0xe2e426c6.toInt(), 0xb2b03282.toInt(),
        0x31303101, 0xe2e82aca.toInt(), 0x616c2d4d, 0x535c1f4f, 0xe0e424c4.toInt(), 0xf0f030c0.toInt(), 0xc1cc0dcd.toInt(), 0x80880888.toInt(),
        0x12141606, 0x32383a0a, 0x50581848, 0xd0d414c4.toInt(), 0x62602242, 0x21282909, 0x03040707, 0x33303303,
        0xe0e828c8.toInt(), 0x13181b0b, 0x01040505, 0x71783949, 0x90901080.toInt(), 0x62682a4a, 0x22282a0a, 0x92981a8a.toInt()
    )

    private val SS3 = intArrayOf(
        0x08303838, 0xc8e0e828.toInt(), 0x0d212c2d, 0x86a2a426.toInt(), 0xcfc3cc0f.toInt(), 0xced2dc1e.toInt(), 0x83b3b033.toInt(), 0x88b0b838.toInt(),
        0x8fa3ac2f.toInt(), 0x40606020, 0x45515415, 0xc7c3c407.toInt(), 0x44404404, 0x4f636c2f, 0x4b63682b, 0x4b53581b,
        0xc3c3c003.toInt(), 0x42626022, 0x03333033, 0x85b1b435.toInt(), 0x09212829, 0x80a0a020.toInt(), 0xc2e2e022.toInt(), 0x87a3a427.toInt(),
        0xc3d3d013.toInt(), 0x81919011.toInt(), 0x01111011, 0x06020406, 0x0c101c1c, 0x8cb0bc3c.toInt(), 0x06323436, 0x4b43480b,
        0xcfe3ec2f.toInt(), 0x88808808.toInt(), 0x4c606c2c, 0x88a0a828.toInt(), 0x07131417, 0xc4c0c404.toInt(), 0x06121416, 0xc4f0f434.toInt(),
        0xc2c2c002.toInt(), 0x45414405, 0xc1e1e021.toInt(), 0xc6d2d416.toInt(), 0x0f333c3f, 0x0d313c3d, 0x8e828c0e.toInt(), 0x88909818.toInt(),
        0x08202828, 0x4e424c0e, 0xc6f2f436.toInt(), 0x0e323c3e, 0x85a1a425.toInt(), 0xc9f1f839.toInt(), 0x0d010c0d, 0xcfd3dc1f.toInt(),
        0xc8d0d818.toInt(), 0x0b23282b, 0x46626426, 0x4a72783a, 0x07232427, 0x0f232c2f, 0xc1f1f031.toInt(), 0x42727032,
        0x42424002, 0xc4d0d414.toInt(), 0x41414001, 0xc0c0c000.toInt(), 0x43737033, 0x47636427, 0x8ca0ac2c.toInt(), 0x8b83880b.toInt(),
        0xc7f3f437.toInt(), 0x8da1ac2d.toInt(), 0x80808000.toInt(), 0x0f131c1f, 0xcac2c80a.toInt(), 0x0c202c2c, 0x8aa2a82a.toInt(), 0x04303434,
        0xc2d2d012.toInt(), 0x0b03080b, 0xcee2ec2e.toInt(), 0xc9e1e829.toInt(), 0x4d515c1d, 0x84909414.toInt(), 0x08101818, 0xc8f0f838.toInt(),
        0x47535417, 0x8ea2ac2e.toInt(), 0x08000808, 0xc5c1c405.toInt(), 0x03131013, 0xcdc1cc0d.toInt(), 0x86828406.toInt(), 0x89b1b839.toInt(),
        0xcff3fc3f.toInt(), 0x4d717c3d, 0xc1c1c001.toInt(), 0x01313031, 0xc5f1f435.toInt(), 0x8a82880a.toInt(), 0x4a62682a, 0x81b1b031.toInt(),
        0xc1d1d011.toInt(), 0x00202020, 0xc7d3d417.toInt(), 0x02020002, 0x02222022, 0x04000404, 0x48606828, 0x41717031,
        0x07030407, 0xcbd3d81b.toInt(), 0x8d919c1d.toInt(), 0x89919819.toInt(), 0x41616021, 0x8eb2bc3e.toInt(), 0xc6e2e426.toInt(), 0x49515819,
        0xcdd1dc1d.toInt(), 0x41515011, 0x80909010.toInt(), 0xccd0dc1c.toInt(), 0x8a92981a.toInt(), 0x83a3a023.toInt(), 0x8ba3a82b.toInt(), 0xc0d0d010.toInt(),
        0x81818001.toInt(), 0x0f030c0f, 0x47434407, 0x0a12181a, 0xc3e3e023.toInt(), 0xcce0ec2c.toInt(), 0x8d818c0d.toInt(), 0x8fb3bc3f.toInt(),
        0x86929416.toInt(), 0x4b73783b, 0x4c505c1c, 0x82a2a022.toInt(), 0x81a1a021.toInt(), 0x43636023, 0x03232023, 0x4d414c0d,
        0xc8c0c808.toInt(), 0x8e929c1e.toInt(), 0x8c909c1c.toInt(), 0x0a32383a, 0x0c000c0c, 0x0e222c2e, 0x8ab2b83a.toInt(), 0x4e626c2e,
        0x8f939c1f.toInt(), 0x4a52581a, 0xc2f2f032.toInt(), 0x82929012.toInt(), 0xc3f3f033.toInt(), 0x49414809, 0x48707838, 0xccc0cc0c.toInt(),
        0x05111415, 0xcbf3f83b.toInt(), 0x40707030, 0x45717435, 0x4f737c3f, 0x05313435, 0x00101010, 0x03030003,
        0x44606424, 0x4d616c2d, 0xc6c2c406.toInt(), 0x44707434, 0xc5d1d415.toInt(), 0x84b0b434.toInt(), 0xcae2e82a.toInt(), 0x09010809,
        0x46727436, 0x09111819, 0xcef2fc3e.toInt(), 0x40404000, 0x02121012, 0xc0e0e020.toInt(), 0x8db1bc3d.toInt(), 0x05010405,
        0xcaf2f83a.toInt(), 0x01010001, 0xc0f0f030.toInt(), 0x0a22282a, 0x4e525c1e, 0x89a1a829.toInt(), 0x46525416, 0x43434003,
        0x85818405.toInt(), 0x04101414, 0x89818809.toInt(), 0x8b93981b.toInt(), 0x80b0b030.toInt(), 0xc5e1e425.toInt(), 0x48404808, 0x49717839,
        0x87939417.toInt(), 0xccf0fc3c.toInt(), 0x0e121c1e, 0x82828002.toInt(), 0x01212021, 0x8c808c0c.toInt(), 0x0b13181b, 0x4f535c1f,
        0x47737437, 0x44505414, 0x82b2b032.toInt(), 0x0d111c1d, 0x05212425, 0x4f434c0f, 0x00000000, 0x46424406,
        0xcde1ec2d.toInt(), 0x48505818, 0x42525012, 0xcbe3e82b.toInt(), 0x4e727c3e, 0xcad2d81a.toInt(), 0xc9c1c809.toInt(), 0xcdf1fc3d.toInt(),
        0x00303030, 0x85919415.toInt(), 0x45616425, 0x0c303c3c, 0x86b2b436.toInt(), 0xc4e0e424.toInt(), 0x8bb3b83b.toInt(), 0x4c707c3c,
        0x0e020c0e, 0x40505010, 0x09313839, 0x06222426, 0x02323032, 0x84808404.toInt(), 0x49616829, 0x83939013.toInt(),
        0x07333437, 0xc7e3e427.toInt(), 0x04202424, 0x84a0a424.toInt(), 0xcbc3c80b.toInt(), 0x43535013, 0x0a02080a, 0x87838407.toInt(),
        0xc9d1d819.toInt(), 0x4c404c0c, 0x83838003.toInt(), 0x8f838c0f.toInt(), 0xcec2cc0e.toInt(), 0x0b33383b, 0x4a42480a, 0x87b3b437.toInt()
    )

    private val KC = intArrayOf(
        0x9e3779b9.toInt(), 0x3c6ef373, 0x78dde6e6, 0xf1bbcdcc.toInt(),
        0xe3779b99.toInt(), 0xc6ef3733.toInt(), 0x8dde6e67.toInt(), 0x1bbcdccf,
        0x3779b99e, 0x6ef3733c, 0xdde6e678.toInt(), 0xbbcdccf1.toInt(),
        0x779b99e3, 0xef3733c6.toInt(), 0xde6e678d.toInt(), 0xbcdccf1b.toInt()
    )
    // @formatter:on

    private fun getB0(a: Int): Int = a and 0xff
    private fun getB1(a: Int): Int = (a ushr 8) and 0xff
    private fun getB2(a: Int): Int = (a ushr 16) and 0xff
    private fun getB3(a: Int): Int = (a ushr 24) and 0xff

    private fun endianChange(v: Int): Int =
        (v ushr 24) or (v shl 24) or ((v shl 8) and 0x00ff0000) or ((v ushr 8) and 0x0000ff00)

    private fun seedRound(
        l0: IntArray, l1: IntArray,
        r0: IntArray, r1: IntArray,
        k: IntArray
    ) {
        var t0 = r0[0] xor k[0]
        var t1 = r1[0] xor k[1]
        t1 = t1 xor t0
        var t00 = if (t0 < 0) (t0.toLong() and 0x7fffffffL) or 0x80000000L else t0.toLong()
        t1 = SS0[getB0(t1)] xor SS1[getB1(t1)] xor SS2[getB2(t1)] xor SS3[getB3(t1)]
        var t11 = if (t1 < 0) (t1.toLong() and 0x7fffffffL) or 0x80000000L else t1.toLong()
        t00 += t11
        t0 = SS0[getB0(t00.toInt())] xor SS1[getB1(t00.toInt())] xor SS2[getB2(t00.toInt())] xor SS3[getB3(t00.toInt())]
        t00 = if (t0 < 0) (t0.toLong() and 0x7fffffffL) or 0x80000000L else t0.toLong()
        t11 += t00
        t1 = SS0[getB0(t11.toInt())] xor SS1[getB1(t11.toInt())] xor SS2[getB2(t11.toInt())] xor SS3[getB3(t11.toInt())]
        t11 = if (t1 < 0) (t1.toLong() and 0x7fffffffL) or 0x80000000L else t1.toLong()
        t00 += t11
        l0[0] = l0[0] xor t00.toInt()
        l1[0] = l1[0] xor t11.toInt()
    }

    private fun bytesToInt(data: ByteArray, offset: Int): Int {
        var v = data[offset].toInt() and 0xff
        v = (v shl 8) xor (data[offset + 1].toInt() and 0xff)
        v = (v shl 8) xor (data[offset + 2].toInt() and 0xff)
        v = (v shl 8) xor (data[offset + 3].toInt() and 0xff)
        return v
    }

    private fun intToBytes(v: Int, out: ByteArray, offset: Int) {
        for (i in 0..3) {
            out[offset + i] = ((v ushr (8 * (3 - i))) and 0xff).toByte()
        }
    }

    fun encrypt(pbData: ByteArray, pdwRoundKey: IntArray, outData: ByteArray) {
        val l0 = intArrayOf(bytesToInt(pbData, 0))
        val l1 = intArrayOf(bytesToInt(pbData, 4))
        val r0 = intArrayOf(bytesToInt(pbData, 8))
        val r1 = intArrayOf(bytesToInt(pbData, 12))
        val k = IntArray(2)
        var n = 0

        for (round in 1..16) {
            k[0] = pdwRoundKey[n++]; k[1] = pdwRoundKey[n++]
            if (round % 2 == 1) seedRound(l0, l1, r0, r1, k)
            else seedRound(r0, r1, l0, l1, k)
        }

        intToBytes(r0[0], outData, 0)
        intToBytes(r1[0], outData, 4)
        intToBytes(l0[0], outData, 8)
        intToBytes(l1[0], outData, 12)
    }

    fun decrypt(pbData: ByteArray, pdwRoundKey: IntArray, outData: ByteArray) {
        val l0 = intArrayOf(bytesToInt(pbData, 0))
        val l1 = intArrayOf(bytesToInt(pbData, 4))
        val r0 = intArrayOf(bytesToInt(pbData, 8))
        val r1 = intArrayOf(bytesToInt(pbData, 12))
        val k = IntArray(2)
        var n = 31

        for (round in 1..16) {
            k[1] = pdwRoundKey[n--]; k[0] = pdwRoundKey[n--]
            if (round % 2 == 1) seedRound(l0, l1, r0, r1, k)
            else seedRound(r0, r1, l0, l1, k)
        }

        intToBytes(r0[0], outData, 0)
        intToBytes(r1[0], outData, 4)
        intToBytes(l0[0], outData, 8)
        intToBytes(l1[0], outData, 12)
    }

    fun generateRoundKey(pdwRoundKey: IntArray, pbUserKey: ByteArray) {
        val a = intArrayOf(bytesToInt(pbUserKey, 0))
        val b = intArrayOf(bytesToInt(pbUserKey, 4))
        val c = intArrayOf(bytesToInt(pbUserKey, 8))
        val d = intArrayOf(bytesToInt(pbUserKey, 12))
        val k = IntArray(2)
        var n = 2

        val t0 = a[0] + c[0] - KC[0]
        val t1 = b[0] - d[0] + KC[0]
        pdwRoundKey[0] = SS0[getB0(t0)] xor SS1[getB1(t0)] xor SS2[getB2(t0)] xor SS3[getB3(t0)]
        pdwRoundKey[1] = SS0[getB0(t1)] xor SS1[getB1(t1)] xor SS2[getB2(t1)] xor SS3[getB3(t1)]

        for (i in 1..15) {
            if (i % 2 == 1) encRoundKeyUpdate0(k, a, b, c, d, i)
            else encRoundKeyUpdate1(k, a, b, c, d, i)
            pdwRoundKey[n++] = k[0]
            pdwRoundKey[n++] = k[1]
        }
    }

    private fun encRoundKeyUpdate0(k: IntArray, a: IntArray, b: IntArray, c: IntArray, d: IntArray, z: Int) {
        val t0 = a[0]
        a[0] = (a[0] ushr 8) xor (b[0] shl 24)
        b[0] = (b[0] ushr 8) xor (t0 shl 24)
        val t00 = a[0] + c[0] - KC[z]
        val t11 = b[0] + KC[z] - d[0]
        k[0] = SS0[getB0(t00)] xor SS1[getB1(t00)] xor SS2[getB2(t00)] xor SS3[getB3(t00)]
        k[1] = SS0[getB0(t11)] xor SS1[getB1(t11)] xor SS2[getB2(t11)] xor SS3[getB3(t11)]
    }

    private fun encRoundKeyUpdate1(k: IntArray, a: IntArray, b: IntArray, c: IntArray, d: IntArray, z: Int) {
        val t0 = c[0]
        c[0] = (c[0] shl 8) xor (d[0] ushr 24)
        d[0] = (d[0] shl 8) xor (t0 ushr 24)
        val t00 = a[0] + c[0] - KC[z]
        val t11 = b[0] + KC[z] - d[0]
        k[0] = SS0[getB0(t00)] xor SS1[getB1(t00)] xor SS2[getB2(t00)] xor SS3[getB3(t00)]
        k[1] = SS0[getB0(t11)] xor SS1[getB1(t11)] xor SS2[getB2(t11)] xor SS3[getB3(t11)]
    }
}
