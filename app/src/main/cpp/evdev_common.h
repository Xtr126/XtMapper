using std::string;

const char *x_virtual_touch = "x-virtual-touch";
const char *x_virtual_mouse = "x-virtual-mouse";

std::vector<string> ListInputDevices();

std::vector<int> scanTouchpadDevices();

bool HasSpecificAbs(int device_fd, unsigned int abs);

bool HasSpecificKey(int device_fd, unsigned int key);

bool HasInputProp(int device_fd, unsigned int input_prop);

bool HasEventType(int device_fd, unsigned int type);
