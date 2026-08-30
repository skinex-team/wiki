package com.skinex.pattern.grpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skinex.pattern.grpc.proto.*;
import com.skinex.pattern.model.PatternInfo;
import com.skinex.pattern.service.PatternService;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class PatternGrpcService extends PatternServiceGrpc.PatternServiceImplBase {

    private final PatternService service;
    private final ObjectMapper mapper;

    public PatternGrpcService(PatternService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public void getPatternInfo(GetPatternInfoRequest req, StreamObserver<GetPatternInfoReply> obs) {
        var opt = service.getInfo(req.getSkin(), req.getSeed());
        if (opt.isEmpty()) {
            boolean hasFeatures = service.hasFeatures(req.getSkin());
            obs.onNext(GetPatternInfoReply.newBuilder()
                    .setFound(false)
                    .setHasFeatures(hasFeatures)
                    .setSkin(req.getSkin())
                    .setSeed(req.getSeed())
                    .build());
            obs.onCompleted();
            return;
        }
        PatternInfo pi = opt.get();
        String floatHint = "";
        if (req.getFloatValue() != 0) {
            double f = req.getFloatValue();
            if (f <= 0.07) floatHint = "Factory New — ярко";
            else if (f <= 0.15) floatHint = "Minimal Wear — ярко";
            else if (f <= 0.38) floatHint = "Field-Tested — темнеет";
            else if (f <= 0.45) floatHint = "Well-Worn — сильно темнеет";
            else floatHint = "Battle-Scarred — глушится";
        }
        obs.onNext(GetPatternInfoReply.newBuilder()
                .setFound(true)
                .setHasFeatures(true)
                .setSkin(pi.skin())
                .setNormalizedSkin(pi.normalizedSkin())
                .setSeed(pi.seed())
                .setCategory(pi.category())
                .setCategoryLabel(pi.categoryLabel() != null ? pi.categoryLabel() : "")
                .setCategoryLabelRu(pi.categoryLabelRu() != null ? pi.categoryLabelRu() : "")
                .setTier(pi.tier() != null ? pi.tier() : 1)
                .setRank(pi.rank() != null ? pi.rank() : 0)
                .setIsBest(pi.isBest())
                .setDisplayName(pi.displayName() != null ? pi.displayName() : "")
                .setDescription(pi.description() != null ? pi.description() : "")
                .setFloatHint(floatHint)
                .build());
        obs.onCompleted();
    }

    @Override
    public void hasFeatures(HasFeaturesRequest req, StreamObserver<HasFeaturesReply> obs) {
        boolean has = service.hasFeatures(req.getSkin());
        obs.onNext(HasFeaturesReply.newBuilder().setHasFeatures(has).setSkin(req.getSkin()).build());
        obs.onCompleted();
    }

    @Override
    public void listSkins(ListSkinsRequest req, StreamObserver<ListSkinsReply> obs) {
        obs.onNext(ListSkinsReply.newBuilder()
                .addAllSkins(service.listSkins())
                .addAllSkinsNormalized(service.listSkinsNormalized())
                .build());
        obs.onCompleted();
    }

    @Override
    public void getSkinPatterns(GetSkinPatternsRequest req, StreamObserver<GetSkinPatternsReply> obs) {
        var opt = service.getSkinPatterns(req.getSkin());
        if (opt.isEmpty()) {
            obs.onNext(GetSkinPatternsReply.newBuilder().setFound(false).setSkin(req.getSkin()).build());
            obs.onCompleted();
            return;
        }
        try {
            String json = mapper.writeValueAsString(opt.get());
            obs.onNext(GetSkinPatternsReply.newBuilder().setFound(true).setSkin(opt.get().skin()).setJson(json).build());
        } catch (Exception e) {
            obs.onNext(GetSkinPatternsReply.newBuilder().setFound(false).setSkin(req.getSkin()).build());
        }
        obs.onCompleted();
    }
}
