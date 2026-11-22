TYPE Ref RECORD k TEXT page INTEGER fingerprint TEXT source NBT
TYPE Pick RECORD s TEXT d Ref b TEXT o ANY
TYPE Order RECORD s TEXT d Ref n INTEGER
TYPE Draft RECORD request Order c TEXT o ANY
REG order Order
REG answer TEXT
REG yes TEXT
REG reply TEXT
REG ok BOOLEAN
TERMINAL.ANSWER answer
CONST yes "YES"
VALUE.EQUALS ok answer yes
CONST reply "cancelled"
JFALSE ok done
FIELD order draft request
XBUS.WRITE x1 order
CONST reply "handed_to_validation"
done:
