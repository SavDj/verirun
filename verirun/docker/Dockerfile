FROM ubuntu:22.04

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y \
    git autoconf make g++ flex bison libfl-dev \
    perl python3 wget curl build-essential \
    libz3-dev zlib1g-dev help2man ca-certificates \
    gtkwave && \
    rm -rf /var/lib/apt/lists/*

RUN git clone https://github.com/verilator/verilator /tmp/verilator && \
    cd /tmp/verilator && \
    autoconf && ./configure && \
    make -j$(nproc) && make install && \
    cd / && rm -rf /tmp/verilator

WORKDIR /uvm
RUN wget --no-check-certificate https://www.accellera.org/images/downloads/standards/uvm/Accellera-1800.2-2017-1.0.tar.gz && \
    tar -xvzf Accellera-1800.2-2017-1.0.tar.gz && \
    rm Accellera-1800.2-2017-1.0.tar.gz

ENV UVM_HOME=/uvm/1800.2-2017-1.0/src
ENV PATH=/usr/local/bin:$PATH

RUN echo "UVM_HOME=$UVM_HOME" && \
    ls -la $UVM_HOME/uvm_pkg.sv && \
    ls -la $UVM_HOME/uvm_macros.svh && \
    verilator --version

WORKDIR /workspace

CMD ["bash"]
