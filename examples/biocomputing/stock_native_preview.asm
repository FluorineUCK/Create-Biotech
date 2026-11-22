; Explicit one-shot data preview. No reader access, ACK, material request or world write.
TYPE Request RECORD n NBT
REG request Request
REG result OUTPUT result
RECORD.MAKE request n order
XBUS.WRITE x0 request
XBUS.READ result x0
