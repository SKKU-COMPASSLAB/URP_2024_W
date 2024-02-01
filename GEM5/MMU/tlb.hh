/*
 * Copyright (c) 2007 The Hewlett-Packard Development Company
 * All rights reserved.
 *
 * The license below extends only to copyright in the software and shall
 * not be construed as granting a license to any other intellectual
 * property including but not limited to intellectual property relating
 * to a hardware implementation of the functionality of the software
 * licensed hereunder.  You may use the software subject to the license
 * terms below provided that you ensure that this notice is replicated
 * unmodified and in its entirety in all distributions of the software,
 * modified or unmodified, in source code or in binary form.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met: redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer;
 * redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution;
 * neither the name of the copyright holders nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#ifndef __ARCH_X86_TLB_HH__
#define __ARCH_X86_TLB_HH__

#include <list>
#include <vector>

#include "arch/generic/tlb.hh"
#include "arch/x86/pagetable.hh"
#include "base/trie.hh"
#include "mem/request.hh"
#include "params/X86TLB.hh"
#include "sim/stats.hh"

namespace gem5
{

class ThreadContext;

namespace X86ISA
{
    class Walker;

    class TLB : public BaseTLB
    {
      protected:
        friend class Walker;

        typedef std::list<TlbEntry *> EntryList;

        uint32_t configAddress;

      public:

        typedef X86TLBParams Params;
        TLB(const Params &p);

        void takeOverFrom(BaseTLB *otlb) override {}

        TlbEntry *lookup(Addr va, bool update_lru = true);

        void setConfigAddress(uint32_t addr);
        //concatenate Page Addr and pcid
        inline Addr concAddrPcid(Addr vpn, uint64_t pcid)
        {
          return (vpn | pcid);
        }

      protected:

        EntryList::iterator lookupIt(Addr va, bool update_lru = true);

        Walker * walker;

      public:
        Walker *getWalker();

        void flushAll() override;

        void flushNonGlobal();

        void demapPage(Addr va, uint64_t asn) override;

      protected:
        uint32_t size;

        std::vector<TlbEntry> tlb;

        EntryList freeList;

        TlbEntryTrie trie;
        uint64_t lruSeq;

        AddrRange m5opRange;

        struct TlbStats : public statistics::Group
        {
            TlbStats(statistics::Group *parent);

            statistics::Scalar rdAccesses;
            statistics::Scalar wrAccesses;
            statistics::Scalar rdMisses;
            statistics::Scalar wrMisses;

            statistics::Scalar innerCacheAccesses;
            statistics::Scalar innerCacheMisses;

            statistics::Scalar innerCachePinnedCount;
        } stats;

        Fault translateInt(bool read, RequestPtr req, ThreadContext *tc);

        Fault translate(const RequestPtr &req, ThreadContext *tc,
                BaseMMU::Translation *translation, BaseMMU::Mode mode,
                bool &delayedResponse, bool timing);

      public:

        void evictLRU();

        uint64_t
        nextSeq()
        {
            return ++lruSeq;
        }

        Fault translateAtomic(
            const RequestPtr &req, ThreadContext *tc,
            BaseMMU::Mode mode) override;
        Fault translateFunctional(
            const RequestPtr &req, ThreadContext *tc,
            BaseMMU::Mode mode) override;
        void translateTiming(
            const RequestPtr &req, ThreadContext *tc,
            BaseMMU::Translation *translation, BaseMMU::Mode mode) override;

        /**
         * Do post-translation physical address finalization.
         *
         * Some addresses, for example requests going to the APIC,
         * need post-translation updates. Such physical addresses are
         * remapped into a "magic" part of the physical address space
         * by this method.
         *
         * @param req Request to updated in-place.
         * @param tc Thread context that created the request.
         * @param mode Request type (read/write/execute).
         * @return A fault on failure, NoFault otherwise.
         */
        Fault finalizePhysical(const RequestPtr &req, ThreadContext *tc,
                               BaseMMU::Mode mode) const override;

        TlbEntry *insert(Addr vpn, const TlbEntry &entry, uint64_t pcid);

        // Checkpointing
        void serialize(CheckpointOut &cp) const override;
        void unserialize(CheckpointIn &cp) override;

        /**
         * Get the table walker port. This is used for
         * migrating port connections during a CPU takeOverFrom()
         * call. For architectures that do not have a table walker,
         * NULL is returned, hence the use of a pointer rather than a
         * reference. For X86 this method will always return a valid
         * port pointer.
         *
         * @return A pointer to the walker port
         */
        Port *getTableWalkerPort() override;

      protected:
        class InnerCache
        {
          protected:
            std::unordered_map<Addr, Addr> cache;
            uint64_t cacheSize;

            std::set<Addr> pinnedList;
            uint64_t maxPinnedSize;

            std::unordered_map<Addr, uint64_t> accessHistory;
            uint64_t pinThreshold;

            std::unordered_map<Addr, uint64_t> lruTable;
            uint64_t currentLRUSeq;

            TlbStats *stats;

            /**
             * Cache 내부 공간에 빈 공간이 있는지 확인한다.
             *
             * @return 비어있다면 true, 꽉 차 있다면 false를 반환한다.
             */
            bool isEmpty() const { return cache.size() < cacheSize; }

            /**
             * PTE를 고정할 수 있는지 확인한다.
             *
             * @return PTE를 고정할 수 있다면 true, 그렇지 않다면 false를 반환한다.
             */
            bool isPinningEmpty() const { return pinnedList.size() < maxPinnedSize; }

            /**
             * Cache 내부 공간에 PTE 존재 여부를 확인한다.
             *
             * @param addr 주소
             * @return PTE가 존재할 경우 true, 존재하지 않는다면 false를 반환한다.
             */
            bool checkExistence(Addr vAddr, Addr pAddr);

            /**
             * Cache 내부 PTE와 외부에서 요청한 PTE가 다를 경우,
             * Cache에 존재하는 PTE를 무효화한다.
             *
             * @param vaddr virtual address
             */
             void invalidate(Addr vAddr);

            /**
             * Cache 내부 공간이 꽉 찬 경우 evict 함수를 호출하여,
             * Replacement Policy에 따라 특정 PTE를 제거한다.
             */
            void evict();

            /**
             * Cache 내부에 PTE를 추가한다.
             * @param addr virtual address
             */
            void insert(Addr vAddr, Addr pAddr);

            /**
             * 주소에 해당하는 접근 횟수가 1회 증가한다.
             *
             * @param addr virtual address
             */
            void increaseCount(Addr vAddr);

            /**
             * 주소에 해당하는 lru count를 업데이트한다.
             *
             * @param addr
             */
            void updateLRUCount(Addr vAddr);

            /**
             * PTE의 Pin 여부를 확인한다.
             *
             * @param addr virtual address
             */
            bool checkPin(Addr vAddr) const { return pinnedList.find(vAddr) != pinnedList.end(); };

            /**
             * vAddr에 해당하는 PTE의 pin 상태를 pinning 알고리즘에 따라 변경한다.
             * @param vAddr virtual address
             */
            void tryPin(Addr vAddr);

            void pin(Addr vAddr);

            void unpin(Addr vaddr);

            /**
             * 현재 Pinning Phase를 확인, 변경하고
             * Phase에 따라 Threshold를 수정한다.
             */
            void checkPinningPhase();

          public:
            InnerCache(uint64_t cacheSize, uint64_t maxPinnedSize, uint64_t pinThreshold, TlbStats *stats) :
                cacheSize(cacheSize), currentLRUSeq(0), stats(stats),
                maxPinnedSize(maxPinnedSize), pinThreshold(pinThreshold)
            { }

            /**
             * InnerCache 작동 진입점
             *
             * 이 함수는 내부에서 PTE 전용 캐시 동작을 모사한다.
             * 모사된 행동을 바탕으로 system stat을 기록하여 hit, miss count를 추출한다.
             *
             * @param addr virtual address
             */
            void checkCacheLatency(Addr vAddr, Addr pAddr);
        };

        InnerCache innerCache;
    };

} // namespace X86ISA
} // namespace gem5

#endif // __ARCH_X86_TLB_HH__
