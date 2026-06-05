import logging

LOGDEBUG = logging.DEBUG
LOGINFO = logging.INFO
LOGNOTICE = logging.INFO
LOGWARNING = logging.WARNING
LOGERROR = logging.ERROR
LOGSEVERE = logging.CRITICAL
LOGFATAL = logging.CRITICAL
LOGNONE = logging.NOTSET

logging.basicConfig(level=logging.WARNING, format='%(name)s [%(levelname)s]: %(message)s')

def execute_jsonrpc(command): return {}

class Logger(object):
    __loggers = {}
    
    @staticmethod
    def get_logger(name="ResolveURL"):
        if name not in Logger.__loggers:
            Logger.__loggers[name] = Logger(name)
        return Logger.__loggers[name]

    def __init__(self, name="ResolveURL"):
        self.logger = logging.getLogger(name)
        self.__disabled = False

    def disable(self): self.__disabled = True
    def enable(self): self.__disabled = False
    
    def log(self, msg, level=LOGDEBUG):
        if not self.__disabled: self.logger.log(level, str(msg))

    def log_debug(self, msg): self.log(msg, level=LOGDEBUG)
    def log_notice(self, msg): self.log(msg, level=LOGINFO)
    def log_warning(self, msg): self.log(msg, level=LOGWARNING)
    def log_error(self, msg): self.log(msg, level=LOGERROR)